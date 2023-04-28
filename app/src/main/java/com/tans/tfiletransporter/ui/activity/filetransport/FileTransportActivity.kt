package com.tans.tfiletransporter.ui.activity.filetransport

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.view.View
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.jakewharton.rxbinding3.view.clicks
import com.tans.rxutils.ignoreSeveralClicks
import com.tans.tfiletransporter.R
import com.tans.tfiletransporter.databinding.FileTransportActivityBinding
import com.tans.tfiletransporter.logs.AndroidLog
import com.tans.tfiletransporter.transferproto.fileexplore.FileExplore
import com.tans.tfiletransporter.transferproto.fileexplore.FileExploreObserver
import com.tans.tfiletransporter.transferproto.fileexplore.FileExploreRequestHandler
import com.tans.tfiletransporter.transferproto.fileexplore.FileExploreState
import com.tans.tfiletransporter.transferproto.fileexplore.Handshake
import com.tans.tfiletransporter.transferproto.fileexplore.bindSuspend
import com.tans.tfiletransporter.transferproto.fileexplore.connectSuspend
import com.tans.tfiletransporter.transferproto.fileexplore.handshakeSuspend
import com.tans.tfiletransporter.transferproto.fileexplore.model.DownloadFilesReq
import com.tans.tfiletransporter.transferproto.fileexplore.model.DownloadFilesResp
import com.tans.tfiletransporter.transferproto.fileexplore.model.ScanDirReq
import com.tans.tfiletransporter.transferproto.fileexplore.model.ScanDirResp
import com.tans.tfiletransporter.transferproto.fileexplore.model.SendFilesReq
import com.tans.tfiletransporter.transferproto.fileexplore.model.SendFilesResp
import com.tans.tfiletransporter.transferproto.fileexplore.model.SendMsgReq
import com.tans.tfiletransporter.transferproto.fileexplore.waitClose
import com.tans.tfiletransporter.transferproto.fileexplore.waitHandshake
import com.tans.tfiletransporter.ui.activity.BaseActivity
import com.tans.tfiletransporter.ui.activity.BaseFragment
import com.tans.tfiletransporter.ui.activity.commomdialog.showLoadingDialog
import com.tans.tfiletransporter.ui.activity.commomdialog.showNoOptionalDialog
import com.tans.tfiletransporter.file.scanChildren
import com.tans.tfiletransporter.viewpager2.FragmentStateAdapter
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.singleton
import java.net.InetAddress
import java.util.Optional
import java.io.File



class FileTransportActivity : BaseActivity<FileTransportActivityBinding, FileTransportActivity.Companion.FileTransportActivityState>(
    R.layout.file_transport_activity, FileTransportActivityState()
) {

    private val floatActionBtnClickEvent: Subject<Unit> by lazy {
        PublishSubject.create<Unit?>().toSerialized()
    }

    val rootDirFile: File by lazy {
        Environment.getExternalStorageDirectory()
    }

    private val scanDirRequest: FileExploreRequestHandler<ScanDirReq, ScanDirResp> by lazy {
        object : FileExploreRequestHandler<ScanDirReq, ScanDirResp> {
            override fun onRequest(isNew: Boolean, request: ScanDirReq): ScanDirResp {
                return request.scanChildren(rootDirFile)
            }
        }
    }

    private val sendFilesRequest: FileExploreRequestHandler<SendFilesReq, SendFilesResp> by lazy {
        object : FileExploreRequestHandler<SendFilesReq, SendFilesResp> {
            override fun onRequest(isNew: Boolean, request: SendFilesReq): SendFilesResp {
                if (isNew) {
                    // TODO: Download files
                }
                return SendFilesResp(bufferSize = 1024 * 512)
            }
        }
    }

    private val downloadFilesRequest: FileExploreRequestHandler<DownloadFilesReq, DownloadFilesResp> by lazy {
        object : FileExploreRequestHandler<DownloadFilesReq, DownloadFilesResp> {
            override fun onRequest(isNew: Boolean, request: DownloadFilesReq): DownloadFilesResp {
                if (isNew) {
                    // TODO: Send files
                }
                return DownloadFilesResp(maxConnection = 8)
            }
        }
    }

    private val fileExplore: FileExplore by lazy {
        FileExplore(
            log = AndroidLog,
            scanDirRequest = scanDirRequest,
            sendFilesRequest = sendFilesRequest,
            downloadFileRequest = downloadFilesRequest
        )
    }

    // private val fileTransportScopeData by instance<FileTransportScopeData>()

    private val fragments: Map<DirTabType, BaseFragment<*, *>> = mapOf(
        DirTabType.MyApps to MyAppsFragment(),
        DirTabType.MyImages to MyImagesFragment(),
        DirTabType.MyDir to MyDirFragment(),
        DirTabType.RemoteDir to RemoteDirFragment(),
        DirTabType.Message to MessageFragment())

    override fun DI.MainBuilder.addDIInstance() {
        bind<FileExplore>() with singleton { fileExplore }
    }

    override fun firstLaunchInitData() {
        val (remoteAddress, isServer, localAddress) = with(intent) { Triple(getRemoteAddress(), getIsServer(), getLocalAddress()) }

        launch(Dispatchers.IO) {
            val loadingDialog = withContext(Dispatchers.Main) {
                showLoadingDialog(cancelable = false)
            }
            val connectResult = if (isServer) {
                AndroidLog.d(TAG, "Start bind address: $localAddress")
                runCatching {
                    withTimeout(5000L) {
                        fileExplore.bindSuspend(address = localAddress)
                    }
                }
            } else {
                AndroidLog.d(TAG, "Start connect address: $remoteAddress")
                var connectTimes = 3
                var connectResult: Result<Unit>
                do {
                    delay(200)
                    connectResult = runCatching {
                        fileExplore.connectSuspend(remoteAddress)
                    }
                    if (connectResult.isSuccess) { break }
                } while (--connectTimes > 0)
                connectResult
            }
            if (connectResult.isSuccess) {
                AndroidLog.d(TAG, "Create connection success!!")
                AndroidLog.d(TAG, "Start handshake.")
                val handshakeResult = if (isServer) {
                    runCatching {
                        withTimeout(3000L) {
                            fileExplore.waitHandshake()
                        }
                    }
                } else {
                    runCatching {
                        fileExplore.handshakeSuspend()
                    }
                }
                if (handshakeResult.isSuccess) {
                    withContext(Dispatchers.Main) { loadingDialog.dismiss() }
                    AndroidLog.d(TAG, "Handshake success!!")
                    updateState { it.copy(handshake = Optional.ofNullable(handshakeResult.getOrNull())) }.await()
                    fileExplore.addObserver(object : FileExploreObserver {
                        override fun onNewState(state: FileExploreState) {}
                        override fun onNewMsg(msg: SendMsgReq) {
                            launch {
                                updateNewMessage(
                                    Message(
                                        time = msg.sendTime,
                                        msg = msg.msg,
                                        fromRemote = true
                                    )
                                )
                            }
                        }
                    })
                    fileExplore.waitClose()
                    withContext(Dispatchers.Main) {
                        showNoOptionalDialog(
                            title = getString(R.string.connection_error_title),
                            message = getString(R.string.connection_error_message)
                        ).await()
                        finish()
                    }
                } else {
                    AndroidLog.e(TAG, "Handshake fail: $handshakeResult", handshakeResult.exceptionOrNull())
                    withContext(Dispatchers.Main) {
                        loadingDialog.dismiss()
                        showNoOptionalDialog(
                            title = getString(R.string.connection_error_title),
                            message = getString(R.string.connection_handshake_error, handshakeResult.exceptionOrNull()?.message ?: "")
                        ).await()
                        finish()
                    }
                }
            } else {
                AndroidLog.e(TAG, "Create connection fail: $connectResult", connectResult.exceptionOrNull())
                withContext(Dispatchers.Main) {
                    loadingDialog.dismiss()
                    showNoOptionalDialog(
                        title = getString(R.string.connection_error_title),
                        message = getString(R.string.connection_connect_error, connectResult.exceptionOrNull()?.message ?: "")
                    ).await()
                    finish()
                }
            }
        }
    }

    override fun initViews(binding: FileTransportActivityBinding) {
        launch {
            val (remoteInfo, remoteAddress) = with(intent) { getRemoteInfo() to getRemoteAddress() }
            binding.toolBar.title = remoteInfo
            binding.toolBar.subtitle = remoteAddress.hostAddress

            render({ it.shareMyDir }) { binding.toolBar.menu.findItem(R.id.share_my_folder).isChecked = it }.bindLife()

            binding.viewPager.adapter = object : FragmentStateAdapter(this@FileTransportActivity) {
                override fun getItemCount(): Int = fragments.size
                override fun createFragment(position: Int): Fragment = fragments[DirTabType.values()[position]]!!
            }

            TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
                tab.text = when (DirTabType.values()[position]) {
                    DirTabType.MyApps -> getString(R.string.file_transport_activity_tab_my_apps)
                    DirTabType.MyImages -> getString(R.string.file_transport_activity_tab_my_images)
                    DirTabType.MyDir -> getString(R.string.file_transport_activity_tab_my_dir)
                    DirTabType.RemoteDir -> getString(R.string.file_transport_activity_tab_remote_dir)
                    DirTabType.Message -> getString(R.string.file_transport_activity_tab_message)
                }
            }.attach()

            binding.toolBar.setOnMenuItemClickListener {
                if (it.itemId == R.id.share_my_folder) {
                    updateState { oldState ->
                        oldState.copy(shareMyDir = !oldState.shareMyDir)
                    }.bindLife()
                    true
                } else {
                    false
                }
            }

            binding.tabLayout.addOnTabSelectedListener(object :
                TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    when (tab?.position) {
                        DirTabType.MyApps.ordinal -> updateStateCompletable { it.copy(selectedTabType = DirTabType.MyApps) }.bindLife()
                        DirTabType.MyImages.ordinal -> updateStateCompletable { it.copy(selectedTabType = DirTabType.MyImages) }.bindLife()
                        DirTabType.MyDir.ordinal -> updateStateCompletable { it.copy(selectedTabType = DirTabType.MyDir) }.bindLife()
                        DirTabType.RemoteDir.ordinal -> updateStateCompletable { it.copy(selectedTabType = DirTabType.RemoteDir) }.bindLife()
                        DirTabType.Message.ordinal -> updateStateCompletable { it.copy(selectedTabType = DirTabType.Message) }.bindLife()
                    }
                }

                override fun onTabUnselected(tab: TabLayout.Tab?) {
                }

                override fun onTabReselected(tab: TabLayout.Tab?) {
                }
            })

            render({ it.selectedTabType }) {

                when (it) {
                    DirTabType.MyApps, DirTabType.MyImages, DirTabType.MyDir, DirTabType.RemoteDir -> {
                        val lpCollapsing = (binding.collapsingLayout.layoutParams as? AppBarLayout.LayoutParams)
                        lpCollapsing?.scrollFlags = AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS_COLLAPSED
                        binding.collapsingLayout.layoutParams = lpCollapsing
                    }
                    DirTabType.Message -> {
                        val lpCollapsing = (binding.collapsingLayout.layoutParams as? AppBarLayout.LayoutParams)
                        lpCollapsing?.scrollFlags = AppBarLayout.LayoutParams.SCROLL_FLAG_NO_SCROLL
                        binding.collapsingLayout.layoutParams = lpCollapsing
                    }
                }

                when (it) {
                    DirTabType.MyApps, DirTabType.MyImages, DirTabType.MyDir -> {
                        binding.floatingActionBt.setImageResource(R.drawable.share_variant_outline)
                        binding.floatingActionBt.visibility = View.VISIBLE
                    }
                    DirTabType.RemoteDir -> {
                        binding.floatingActionBt.setImageResource(R.drawable.download_outline)
                        binding.floatingActionBt.visibility = View.VISIBLE
                    }
                    DirTabType.Message -> {
                        binding.floatingActionBt.visibility = View.GONE
                    }
                }
                binding.appbarLayout.setExpanded(true, true)
            }.bindLife()


            binding.floatingActionBt.clicks()
                .ignoreSeveralClicks()
                .doOnNext { floatActionBtnClickEvent.onNext(Unit) }
                .bindLife()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        fileExplore.closeConnectionIfActive()
    }

    fun observeFloatBtnClick(): Observable<Unit> = floatActionBtnClickEvent

    fun observeMessages(): Observable<List<Message>> = bindState().map { it.messages }.distinctUntilChanged()

    suspend fun updateNewMessage(msg: Message) {
        updateState { it.copy(messages = it.messages + msg) }.await()
    }

    companion object {

        private const val TAG = "FileTransporterActivity"

        private const val LOCAL_ADDRESS_EXTRA_KEY = "local_address_extra_key"
        private const val REMOTE_ADDRESS_EXTRA_KEY = "remote_address_extra_key"
        private const val REMOTE_INFO_EXTRA_KEY = "remote_info_extra_key"
        private const val IS_SERVER_EXTRA_KEY = "is_server_extra_key"

        private fun Intent.getLocalAddress(): InetAddress = getSerializableExtra(
            LOCAL_ADDRESS_EXTRA_KEY
        ) as? InetAddress ?: error("FileTransportActivity get local address fail.")

        private fun Intent.getRemoteAddress(): InetAddress = getSerializableExtra(
            REMOTE_ADDRESS_EXTRA_KEY
        ) as? InetAddress ?: error("FileTransportActivity get remote address fail.")

        private fun Intent.getRemoteInfo(): String = getStringExtra(REMOTE_INFO_EXTRA_KEY) ?: ""

        private fun Intent.getIsServer(): Boolean = getBooleanExtra(IS_SERVER_EXTRA_KEY, false)

        data class Message(
            val time: Long,
            val msg: String,
            val fromRemote: Boolean
        )

        data class FileTransportActivityState(
            val selectedTabType: DirTabType = DirTabType.MyApps,
            val handshake: Optional<Handshake> = Optional.empty(),
            // val connectionStatus: ConnectionStatus = ConnectionStatus.Connecting,
            val shareMyDir: Boolean = false,
            val messages: List<Message> = emptyList()
        )

        enum class DirTabType {
            MyApps,
            MyImages,
            MyDir,
            RemoteDir,
            Message
        }

        fun getIntent(context: Context,
                      localAddress: InetAddress,
                      remoteAddress: InetAddress,
                      remoteDeviceInfo: String,
                      isServer: Boolean): Intent {
            val i = Intent(context, FileTransportActivity::class.java)
            i.putExtra(LOCAL_ADDRESS_EXTRA_KEY, localAddress)
            i.putExtra(REMOTE_ADDRESS_EXTRA_KEY, remoteAddress)
            i.putExtra(REMOTE_INFO_EXTRA_KEY, remoteDeviceInfo)
            i.putExtra(IS_SERVER_EXTRA_KEY, isServer)
            return i
        }
    }

}