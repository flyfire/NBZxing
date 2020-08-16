package com.ailiwean.core.view

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.PointF
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.provider.MediaStore
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.ailiwean.core.Config.*
import com.ailiwean.core.Result
import com.ailiwean.core.Utils
import com.ailiwean.core.able.AbleManager
import com.ailiwean.core.helper.VibrateHelper
import com.ailiwean.core.zxing.BitmapLuminanceSource
import com.ailiwean.core.zxing.CustomMultiFormatReader
import com.ailiwean.core.zxing.ScanTypeConfig
import com.ailiwean.core.zxing.core.BinaryBitmap
import com.ailiwean.core.zxing.core.common.GlobalHistogramBinarizer
import com.ailiwean.core.zxing.core.common.HybridBinarizer
import com.google.android.cameraview.AspectRatio
import com.google.android.cameraview.BaseCameraView
import com.google.android.cameraview.CameraView
import com.google.android.cameraview.R
import kotlinx.android.synthetic.main.base_zxing_layout.view.*
import java.io.File
import java.lang.ref.WeakReference

/**
 * @Package:        com.google.android.cameraview
 * @ClassName:      ZxingCamera
 * @Description:
 * @Author:         SWY
 * @CreateDate:     2020/4/19 12:38 AM
 */
abstract class ZxingCameraView @JvmOverloads constructor(context: Context, attributeSet: AttributeSet? = null, def: Int = 0) :
        BaseCameraView(context, attributeSet, def), Handler.Callback {

    init {
        facing = FACING_BACK
        this.setAspectRatio(AspectRatio.of(16, 9))
        this.addView(LayoutInflater.from(context).inflate(R.layout.base_zxing_layout, this, false)
                , ViewGroup.LayoutParams(-1, -1))

        //注册打开关闭闪光灯点击事件
        lightView.regLightClick {
            lightOperator(it)
        }
    }

    private val handleZX = HandleZX(this)

    override fun handleMessage(it: Message): Boolean {
        when (it.what) {
            SCAN_RESULT -> {
                scanSucHelper()
                if (it.obj is Result) {
                    showQRLoc((it.obj as Result).pointF, it.obj.toString())
                }
            }
            LIGHT_CHANGE -> {
                lightView.setBright(it.obj.toString().toBoolean())
            }

            AUTO_ZOOM -> {
                setZoom(it.obj.toString().toFloat())
            }
        }
        return true
    }

    private var ableCollect: AbleManager? = null

    override fun onPreviewByte(camera: CameraView, data: ByteArray) {
        super.onPreviewByte(camera, data)
        val dataWidht = scanRect.dataX
        val dataHeight = scanRect.dataY
        ableCollect?.cusAction(data, dataWidht, dataHeight)
    }


    /***
     * 扫码成功后的一些动作
     */
    fun scanSucHelper() {
        onComPause()
        scan_bar.stopAnim()
        VibrateHelper.playVibrate()
        VibrateHelper.playBeep()
    }

    override fun onCreate() {
        super.onCreate()
        ableCollect = AbleManager.createInstance(handleZX)
    }

    override fun onResume() {
        super.onResume()
        initConfig()
    }

    override fun onPause() {
        super.onPause()
        scan_bar.stopAnim()
    }

    override fun onDestroy() {
        super.onDestroy()
        ableCollect?.release()
        busHandle?.looper?.quit()
    }

    /***
     * 显示二维码位置, 动画播放完回调扫描结果
     */
    fun showQRLoc(point: PointF, content: String) {
        qr_loc.visibility = View.VISIBLE
        qr_loc.translationX = point.x - Utils.dp2px(25f)
        qr_loc.translationY = point.y - Utils.dp2px(25f)
        qr_loc.scaleX = 0f
        qr_loc.scaleY = 0f
        qr_loc.animate().scaleX(1f)
                .scaleY(1f)
                .setDuration(300)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator?) {
                        resultBack(content)
                    }
                })
                .start()
    }

    /***
     * 相机启动数据初始化
     */
    fun initConfig() {
        ableCollect?.loadAble()
        scan_bar.startAnim()
        qr_loc.visibility = View.INVISIBLE
        initScanType()
        handleZX.init()
        VibrateHelper.playInit()
    }

    /***
     * 扫码结果回调
     */
    abstract fun resultBack(content: String)


    protected open fun resultBackFile(content: String) {

    }


    class HandleZX constructor(view: Callback) : Handler() {
        var hasResult = false
        var viewReference: WeakReference<Callback>? = null

        init {
            this@HandleZX.viewReference = WeakReference(view)
        }

        override fun handleMessage(msg: Message?) {
            if (hasResult)
                return
            msg?.apply {
                if (msg.what == SCAN_RESULT)
                    hasResult = true
                this@HandleZX.viewReference?.get()?.let {
                    it.handleMessage(msg)
                }
            }
        }

        fun init() {
            hasResult = false
        }
    }

    @SuppressLint("WrongViewCast")
    fun getView(): View {
        return findViewById(R.id.base_floor)
    }

    private fun initScanType() {
        scanTypeConfig = getScanType()
    }

    open fun getScanType(): ScanTypeConfig {
        return ScanTypeConfig.HIGH_FREQUENCY
    }

    var busHandle: Handler? = null

    private fun initBusHandle() {
        val handlerThread = HandlerThread(System.currentTimeMillis().toString())
        handlerThread.start()
        busHandle = Handler(handlerThread.looper)
    }

    protected fun parseFile(filePath: String) {

        if (!checkPermissionRW())
            return

        val file = File(filePath)
        if (!file.exists())
            return

        if (busHandle == null)
            initBusHandle()

        busHandle?.post {
            val bitmap: Bitmap = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, getMediaUriFromPath(context, filePath)))
                        .copy(Bitmap.Config.RGB_565, false)
            } else
                BitmapFactory.decodeFile(filePath))
                    ?: return@post
            parseBitmap(bitmap)
        }
    }

    protected fun parseBitmap(bitmap: Bitmap?) {
        if (bitmap == null)
            return
        if (busHandle == null)
            initBusHandle()
        busHandle?.post {
            bitmap.apply {
                if (config != Bitmap.Config.RGB_565
                        && config != Bitmap.Config.ARGB_8888) {
                    if (isMutable)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                            config = Bitmap.Config.RGB_565
                        } else {
                            copy(Bitmap.Config.RGB_565, false)
                        }
                    else
                        copy(Bitmap.Config.RGB_565, false)
                }
                val source = BitmapLuminanceSource(this)
                var result = CustomMultiFormatReader.getInstance()
                        .decode(BinaryBitmap(GlobalHistogramBinarizer(source)))
                if (result == null)
                    result = CustomMultiFormatReader.getInstance()
                            .decode(BinaryBitmap(HybridBinarizer(source)))
                if (result != null) {
                    mainHand.post {
                        resultBackFile(result.text)
                        scanSucHelper()
                    }
                } else {
                    mainHand.post {
                        resultBackFile("")
                    }
                }
            }
        }
    }

    open fun getMediaUriFromPath(context: Context, path: String): Uri {
        val mediaUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val cursor: Cursor? = context.contentResolver.query(mediaUri,
                null,
                MediaStore.Images.Media.DISPLAY_NAME + "= ?", arrayOf(path.substring(path.lastIndexOf("/") + 1)),
                null)
        var uri: Uri? = null
        cursor?.let {
            it.moveToFirst()
            uri = ContentUris.withAppendedId(mediaUri,
                    it.getLong(it.getColumnIndex(MediaStore.Images.Media._ID)))
        }
        cursor?.close()
        return uri ?: Uri.EMPTY
    }
}