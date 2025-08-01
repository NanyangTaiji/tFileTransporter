package com.tans.tfiletransporter.transferproto.broadcastconn

import com.tans.tfiletransporter.ILog
import com.tans.tfiletransporter.netty.INettyConnectionTask
import com.tans.tfiletransporter.netty.NettyConnectionObserver
import com.tans.tfiletransporter.netty.NettyTaskState
import com.tans.tfiletransporter.netty.PackageData
import com.tans.tfiletransporter.netty.extensions.ConnectionClientImpl
import com.tans.tfiletransporter.netty.extensions.ConnectionServerImpl
import com.tans.tfiletransporter.netty.extensions.IClientManager
import com.tans.tfiletransporter.netty.extensions.IServer
import com.tans.tfiletransporter.netty.extensions.requestSimplify
import com.tans.tfiletransporter.netty.extensions.simplifyServer
import com.tans.tfiletransporter.netty.extensions.withClient
import com.tans.tfiletransporter.netty.extensions.withServer
import com.tans.tfiletransporter.netty.udp.NettyUdpConnectionTask
import com.tans.tfiletransporter.netty.udp.NettyUdpConnectionTask.Companion.ConnectionType
import com.tans.tfiletransporter.transferproto.SimpleCallback
import com.tans.tfiletransporter.transferproto.SimpleObservable
import com.tans.tfiletransporter.transferproto.SimpleStateable
import com.tans.tfiletransporter.transferproto.TransferProtoConstant
import com.tans.tfiletransporter.transferproto.broadcastconn.model.BroadcastDataType
import com.tans.tfiletransporter.transferproto.broadcastconn.model.BroadcastMsg
import com.tans.tfiletransporter.transferproto.broadcastconn.model.BroadcastTransferFileReq
import com.tans.tfiletransporter.transferproto.broadcastconn.model.BroadcastTransferFileResp
import com.tans.tfiletransporter.transferproto.broadcastconn.model.RemoteDevice
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference


/**
 * [BroadcastSender] send UDP broadcast message ([BroadcastMsg]) on port [TransferProtoConstant.BROADCAST_SCANNER_PORT], [BroadcastReceiver] could receive this message when [BroadcastReceiver] and [BroadcastSender] in the same network.
 * And BroadcastSender will listen BroadcastReceiver request to create FileExplore connection, listen use UDP [TransferProtoConstant.BROADCAST_TRANSFER_SERVER_PORT] port.
 */
class BroadcastSender(
    private val deviceName: String,
    private val log: ILog,
    private val broadcastSendIntervalMillis: Long = 1000
) : SimpleObservable<BroadcastSenderObserver>, SimpleStateable<BroadcastSenderState> {

    override val state: AtomicReference<BroadcastSenderState> = AtomicReference(BroadcastSenderState.NoConnection)

    override val observers: LinkedBlockingDeque<BroadcastSenderObserver> = LinkedBlockingDeque()

    /**
     * Client transfer request server.
     */
    private val transferServer: IServer<BroadcastTransferFileReq, BroadcastTransferFileResp> by lazy {
        simplifyServer(
            requestType = BroadcastDataType.TransferFileReq.type,
            responseType = BroadcastDataType.TransferFileResp.type,
            log = log,
            onRequest = { _, rr, r, isNewRequest ->
                // 收到 client 的请求链接的信息
                if (rr == null || r.version != TransferProtoConstant.VERSION) {
                    null
                } else {
                    if (isNewRequest) {
                        dispatchTransferReq(rr, r)
                    }
                    BroadcastTransferFileResp(deviceName = deviceName)
                }
            }
        )
    }

    private val sendFuture: AtomicReference<ScheduledFuture<*>?> by lazy {
        AtomicReference(null)
    }

    private val broadcastSenderTask: AtomicReference<ConnectionClientImpl?> by lazy {
        AtomicReference(null)
    }

    private val requestReceiverTask: AtomicReference<ConnectionServerImpl?> by lazy {
        AtomicReference(null)
    }

    // 监控链接断开
    private val closeObserver: NettyConnectionObserver by lazy {
        object : NettyConnectionObserver {
            override fun onNewState(nettyState: NettyTaskState, task: INettyConnectionTask) {
                if (nettyState is NettyTaskState.ConnectionClosed || nettyState is NettyTaskState.Error) {
                    closeConnectionIfActive()
                }
            }

            override fun onNewMessage(
                localAddress: InetSocketAddress?,
                remoteAddress: InetSocketAddress?,
                msg: PackageData,
                task: INettyConnectionTask
            ) {}
        }
    }

    /**
     * Broadcast send task.
     */
    // 发送广播消息任务
    private val senderBroadcastTask: Runnable by lazy {
        Runnable {
            val state = getCurrentState()
            if (state is BroadcastSenderState.Active) {
                log.d(TAG, "Send broadcast.")
                broadcastSenderTask.get()?.requestSimplify<BroadcastMsg, Unit>(
                    type = BroadcastDataType.BroadcastMsg.type,
                    request = BroadcastMsg(
                        version = TransferProtoConstant.VERSION,
                        deviceName = deviceName
                    ),
                    retryTimes = 0,
                    targetAddress = InetSocketAddress(state.broadcastAddress, TransferProtoConstant.BROADCAST_SCANNER_PORT),
                    callback = object : IClientManager.RequestCallback<Unit> {
                        override fun onSuccess(
                            type: Int,
                            messageId: Long,
                            localAddress: InetSocketAddress?,
                            remoteAddress: InetSocketAddress?,
                            d: Unit
                        ) {}
                        override fun onFail(errorMsg: String) {}
                    }
                )
            } else {
                log.e(TAG, "Send broadcast fail, wrong state: $state")
            }
        }
    }

    override fun addObserver(o: BroadcastSenderObserver) {
        super.addObserver(o)
        o.onNewState(getCurrentState())
    }

    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    fun startBroadcastSender(
        localAddress: InetAddress,
        broadcastAddress: InetAddress,
        simpleCallback: SimpleCallback<Unit>) {
        val currentState = getCurrentState()
        if (currentState != BroadcastSenderState.NoConnection) {
            simpleCallback.onError("Wrong state: $currentState")
        }
        newState(BroadcastSenderState.Requesting)
        val hasInvokeCallback = AtomicBoolean(false)
        // Broadcast send task.
        // 发送 Udp 广播信息的任务
        val senderTask = NettyUdpConnectionTask(
            connectionType = ConnectionType.Connect(
                address = broadcastAddress,
                port = TransferProtoConstant.BROADCAST_SCANNER_PORT
            ),
            enableBroadcast = true
        ).withClient<ConnectionClientImpl>(log = log)
        this.broadcastSenderTask.get()?.stopTask()
        this.broadcastSenderTask.set(senderTask)

        // Receive client transfer file request task.
        // 接受 Client 请求的 Server
        val requestReceiverTask = NettyUdpConnectionTask(
            connectionType = ConnectionType.Bind(
                address = localAddress,
                port = TransferProtoConstant.BROADCAST_TRANSFER_SERVER_PORT
            )
        ).withServer<ConnectionServerImpl>(log = log)
        requestReceiverTask.registerServer(transferServer)
        this.requestReceiverTask.get()?.stopTask()
        this.requestReceiverTask.set(requestReceiverTask)

        senderTask.addObserver(object : NettyConnectionObserver {
            override fun onNewMessage(
                localAddress: InetSocketAddress?,
                remoteAddress: InetSocketAddress?,
                msg: PackageData,
                task: INettyConnectionTask
            ) {}
            override fun onNewState(senderState: NettyTaskState, task: INettyConnectionTask) {
                if (senderState is NettyTaskState.ConnectionClosed
                    || senderState is NettyTaskState.Error
                    || getCurrentState() !is BroadcastSenderState.Requesting
                ) {
                    // Udp 广播发送任务启动失败
                    // Broadcast sender task fail.
                    log.e(TAG, "Sender task error: $senderState, ${getCurrentState()}")
                    if (hasInvokeCallback.compareAndSet(false, true)) {
                        simpleCallback.onError(senderState.toString())
                    }
                    newState(BroadcastSenderState.NoConnection)
                    senderTask.removeObserver(this)
                    senderTask.stopTask()
                } else {
                    // Udp 广播发送任务启动成功
                    // Broadcast sender task success.
                    if (senderState is NettyTaskState.ConnectionActive) {
                        log.d(TAG, "Sender task connect success")
                        requestReceiverTask.addObserver(object : NettyConnectionObserver {

                            override fun onNewMessage(
                                localAddress: InetSocketAddress?,
                                remoteAddress: InetSocketAddress?,
                                msg: PackageData,
                                task: INettyConnectionTask
                            ) {}

                            override fun onNewState(
                                receiverState: NettyTaskState,
                                task: INettyConnectionTask
                            ) {
                                if (receiverState is NettyTaskState.ConnectionClosed
                                    || receiverState is NettyTaskState.Error
                                    || senderTask.getCurrentState() !is NettyTaskState.ConnectionActive
                                    || getCurrentState() !is BroadcastSenderState.Requesting
                                ) {
                                    // 接受 Client 请求的任务启动失败
                                    // Receive client request task fail.
                                    log.d(TAG, "Request task bind fail: $receiverState, ${senderTask.getCurrentState()}, ${getCurrentState()}")
                                    if (hasInvokeCallback.compareAndSet(false, true)) {
                                        simpleCallback.onError(receiverState.toString())
                                    }
                                    newState(BroadcastSenderState.NoConnection)
                                    requestReceiverTask.removeObserver(this)
                                    requestReceiverTask.stopTask()
                                    senderTask.stopTask()
                                } else {
                                    // 接受 Client 请求的任务启动成功
                                    // Receive client request task success.
                                    if (receiverState is NettyTaskState.ConnectionActive) {
                                        log.d(TAG, "Request task bind success")
                                        if (hasInvokeCallback.compareAndSet(false, true)) {
                                            simpleCallback.onSuccess(Unit)
                                        }
                                        // Send one broadcast each second (default)
                                        // 定时循环发送广播消息
                                        val senderFuture = taskScheduleExecutor.scheduleWithFixedDelay(
                                            senderBroadcastTask,
                                            500,
                                            broadcastSendIntervalMillis, TimeUnit.MILLISECONDS
                                        )
                                        this@BroadcastSender.sendFuture.get()?.cancel(true)
                                        this@BroadcastSender.sendFuture.set(senderFuture)
                                        newState(
                                            BroadcastSenderState.Active(
                                                broadcastAddress = broadcastAddress)
                                        )
                                        // 监控链接断开
                                        senderTask.addObserver(closeObserver)
                                        requestReceiverTask.addObserver(closeObserver)
                                    }
                                }
                            }
                        })
                        /**
                         * Step2: Start Receive client transfer file request task.
                         */
                        // 启动接受 Client 请求链接的任务
                        requestReceiverTask.startTask()
                        senderTask.removeObserver(this)
                    }
                }
            }
        })

        /**
         * Step1: Start broadcast sender task.
         */
        // 启动 Udp 广播信息任务
        senderTask.startTask()
    }


    fun closeConnectionIfActive() {
        sendFuture.get()?.cancel(true)
        sendFuture.set(null)
        broadcastSenderTask.get()?.stopTask()
        broadcastSenderTask.set(null)
        requestReceiverTask.get()?.stopTask()
        requestReceiverTask.set(null)
        newState(BroadcastSenderState.NoConnection)
        clearObserves()
    }

    override fun onNewState(s: BroadcastSenderState) {
        super.onNewState(s)
        for (o in observers) {
            o.onNewState(s)
        }
    }

    private fun dispatchTransferReq(remoteAddress: InetSocketAddress, req: BroadcastTransferFileReq) {
        val rd = RemoteDevice(
            remoteAddress = remoteAddress,
            deviceName = req.deviceName
        )
        for (o in observers) {
            o.requestTransferFile(rd)
        }
    }

    companion object {
        private const val TAG = "BroadcastSender"
        private val taskScheduleExecutor: ScheduledExecutorService by lazy {
            Executors.newScheduledThreadPool(1) {
                Thread(it, "BroadcastTaskThread")
            }
        }
    }
}