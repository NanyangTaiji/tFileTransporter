package com.tans.tfiletransporter.transferproto.qrscanconn

import com.tans.tfiletransporter.ILog
import com.tans.tfiletransporter.netty.INettyConnectionTask
import com.tans.tfiletransporter.netty.NettyConnectionObserver
import com.tans.tfiletransporter.netty.NettyTaskState
import com.tans.tfiletransporter.netty.PackageData
import com.tans.tfiletransporter.netty.extensions.ConnectionServerImpl
import com.tans.tfiletransporter.netty.extensions.IServer
import com.tans.tfiletransporter.netty.extensions.simplifyServer
import com.tans.tfiletransporter.netty.extensions.withServer
import com.tans.tfiletransporter.netty.udp.NettyUdpConnectionTask
import com.tans.tfiletransporter.transferproto.SimpleCallback
import com.tans.tfiletransporter.transferproto.SimpleObservable
import com.tans.tfiletransporter.transferproto.SimpleStateable
import com.tans.tfiletransporter.transferproto.TransferProtoConstant
import com.tans.tfiletransporter.transferproto.broadcastconn.model.RemoteDevice
import com.tans.tfiletransporter.transferproto.qrscanconn.model.QRCodeTransferFileReq
import com.tans.tfiletransporter.transferproto.qrscanconn.model.QrScanDataType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * [QRCodeScanServer] create a QRCode Image, contains Server's IP address, version and device name([com.tans.tfiletransporter.transferproto.qrscanconn.model.QRCodeShare]).
 * And [QRCodeScanServer] bind UDP port [TransferProtoConstant.QR_CODE_SCAN_SERVER_PORT], waiting [QRCodeScanClient] to connect.
 * After connection created, [QRCodeScanServer] waits client send [QrScanDataType.TransferFileReq] request, body is [QRCodeTransferFileReq], to create FileExplore connection.
 *
 */
class QRCodeScanServer(private val log: ILog) : SimpleObservable<QRCodeScanServerObserver>, SimpleStateable<QRCodeScanState> {

    override val observers: LinkedBlockingDeque<QRCodeScanServerObserver> = LinkedBlockingDeque()

    override val state: AtomicReference<QRCodeScanState> = AtomicReference(QRCodeScanState.NoConnection)

    private val connectionTask: AtomicReference<ConnectionServerImpl?> = AtomicReference(null)

    private val transferFileServer: IServer<QRCodeTransferFileReq, Unit> by lazy {
        simplifyServer(
            requestType = QrScanDataType.TransferFileReq.type,
            responseType = QrScanDataType.TransferFileResp.type,
            log = log,
            // New client request coming.
            onRequest = { _, ra, r, isNew ->
                // 客户端请求链接
                if (ra != null && isNew) {
                    val currentState = getCurrentState()
                    if (currentState == QRCodeScanState.Active && r.version == TransferProtoConstant.VERSION) {
                        Dispatchers.IO.asExecutor().execute {
                            val remoteDevice = RemoteDevice(remoteAddress = ra, deviceName = r.deviceName)
                            for (o in observers) {
                                o.requestTransferFile(remoteDevice)
                            }
                        }
                    } else {
                        log.e(TAG, "Receive $r, but state is error: $currentState")
                        return@simplifyServer null
                    }
                }
                Unit
            }
        )
    }

    override fun addObserver(o: QRCodeScanServerObserver) {
        super.addObserver(o)
        o.onNewState(state.get())
    }

    fun startQRCodeScanServer(localAddress: InetAddress, callback: SimpleCallback<Unit>) {
        if (getCurrentState() != QRCodeScanState.NoConnection) {
            val eMsg = "Wrong state: ${getCurrentState()}"
            log.e(TAG, eMsg)
            callback.onError(eMsg)
            return
        }
        newState(QRCodeScanState.Requesting)
        // Wait client request transfer file task.
        // 等待链接服务任务
        val connectionTask = NettyUdpConnectionTask(
            connectionType = NettyUdpConnectionTask.Companion.ConnectionType.Bind(
                address = localAddress,
                port = TransferProtoConstant.QR_CODE_SCAN_SERVER_PORT
            )
        ).withServer<ConnectionServerImpl>(log = log)
        // 注册等待链接服务
        connectionTask.registerServer(transferFileServer)
        this.connectionTask.get()?.stopTask()
        this.connectionTask.set(connectionTask)
        val hasInvokeCallback = AtomicBoolean(false)
        connectionTask.addObserver(object : NettyConnectionObserver {
            override fun onNewState(nettyState: NettyTaskState, task: INettyConnectionTask) {
                if (nettyState is NettyTaskState.Error || nettyState is NettyTaskState.ConnectionClosed) {
                    // 等待链接服务启动失败
                    // Wait client task connection fail.
                    val eMsg = "Connection error: $nettyState"
                    log.e(TAG, eMsg)
                    if (hasInvokeCallback.compareAndSet(false, true)) {
                        callback.onError(eMsg)
                    }
                    closeConnectionIfActive()
                }
                if (nettyState is NettyTaskState.ConnectionActive) {
                    // 等待链接服务启动成功
                    // Wait client task connection success.
                    val currentState = getCurrentState()
                    if (currentState == QRCodeScanState.Requesting) {
                        log.d(TAG, "Connection is active.")
                        if (hasInvokeCallback.compareAndSet(false, true)) {
                            callback.onSuccess(Unit)
                        }
                        newState(QRCodeScanState.Active)
                    } else {
                        val eMsg = "Error current state: $currentState"
                        log.e(TAG, eMsg)
                        if (hasInvokeCallback.compareAndSet(false, true)) {
                            callback.onError(eMsg)
                        }
                        closeConnectionIfActive()
                    }
                }
            }

            override fun onNewMessage(
                localAddress: InetSocketAddress?,
                remoteAddress: InetSocketAddress?,
                msg: PackageData,
                task: INettyConnectionTask
            ) {}
        })
        /**
         * Step1: Start wait client request transfer file task.
         */
        // 启动等待链接服务任务
        connectionTask.startTask()
    }

    override fun onNewState(s: QRCodeScanState) {
        for (o in observers) {
            o.onNewState(s)
        }
    }

    fun closeConnectionIfActive() {
        newState(QRCodeScanState.NoConnection)
        connectionTask.get()?.let {
            it.stopTask()
            connectionTask.set(null)
        }
        clearObserves()
    }

    companion object {
        private const val TAG = "QRCodeScanServer"
    }
}