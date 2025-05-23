package com.nt4f04und.android_content_provider

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.*
import androidx.annotation.CallSuper
import androidx.annotation.RequiresApi
import androidx.lifecycle.Lifecycle
import com.nt4f04und.android_content_provider.CompatLifecycleOwner;
import com.nt4f04und.android_content_provider.AndroidContentProvider.Companion.getFlutterEngineGroup
import io.flutter.FlutterInjector
import io.flutter.Log
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineGroup
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.io.FileNotFoundException
import java.lang.Exception


/** A [ContentProvider] for [AndroidContentProviderPlugin].
 *
 * ### Lifecycle
 *
 * All [AndroidContentProvider]s create their own dedicated [FlutterEngine]
 * from the [FlutterEngineGroup] returned by [getFlutterEngineGroup].
 * This means each content provider will have its own isolate.
 *
 * In Android, [ContentProvider]s can be created by the system on demand from background.
 * Once the content provider is created, it is not destroyed, until the app process is.
 */
abstract class AndroidContentProvider : ContentProvider(), Utils {
    private lateinit var engine: FlutterEngine
    private lateinit var methodChannel: SynchronousMethodChannel
    private lateinit var trackingMapFactory: TrackingMapFactory
    private lateinit var lifecycleOwner: CompatLifecycleOwner

    companion object {
        private var engineGroup: FlutterEngineGroup? = null

        /**
         * Returns the [FlutterEngineGroup] used to create engines from [AndroidContentProvider]s.
         *
         * You should use this engine group in your own components, because it drastically
         * [improves performance and reduces memory footprint](https://flutter.dev/docs/development/add-to-app/multiple-flutters).
         *
         * See the [link](https://github.com/nt4f04uNd/android_content_provider#configuring-androidcontentprovider)
         * for instructions how to do that.
         */
        fun getFlutterEngineGroup(context: Context): FlutterEngineGroup {
            if (engineGroup == null) {
                engineGroup = FlutterEngineGroup(context.applicationContext)
            }
            return engineGroup!!
        }

        /**
         * Set the engine group used by plugin before it is initialized
         * from call to [getFlutterEngineGroup]. If already initialized,
         * will do nothing.
         *
         * Meant to be called from [Application.onCreate] to allow
         * sharing [FlutterEngineGroup] between multiple plugins.
         */
        fun presetFlutterEngineGroup(value: FlutterEngineGroup) {
            if (engineGroup == null) {
                engineGroup = value
            }
        }
    }

    /**
     * Should be set to this [ContentProvider]'s authority
     * it's declared with in manifest.
     */
    abstract val authority: String

    /**
     * Should be set to a entrypoint name this [ContentProvider]
     * will call in Dart when it's created.
     *
     * Each content provider must have its unique entrypoint.
     */
    abstract val entrypointName: String // TODO: eventually replace it with initialArguments

    @CallSuper
    override fun onCreate(): Boolean {
        val flutterLoader = FlutterInjector.instance().flutterLoader()
        flutterLoader.startInitialization(context!!.applicationContext)
        val entrypoint = DartExecutor.DartEntrypoint(flutterLoader.findAppBundlePath(), entrypointName)
        val engineGroup = getFlutterEngineGroup(context!!)
        lifecycleOwner = CompatLifecycleOwner()
        lifecycleOwner.lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        engine = engineGroup.createAndRunEngine(context!!, entrypoint)
        engine.contentProviderControlSurface.attachToContentProvider(this, lifecycleOwner.lifecycle)
        trackingMapFactory = TrackingMapFactory(engine.dartExecutor.binaryMessenger)
        methodChannel = SynchronousMethodChannel(MethodChannel(
                engine.dartExecutor.binaryMessenger,
                "${AndroidContentProviderPlugin.channelPrefix}/ContentProvider/$authority",
                AndroidContentProviderPlugin.pluginMethodCodec,
                engine.dartExecutor.binaryMessenger.makeBackgroundTaskQueue(
                    BinaryMessenger.TaskQueueOptions().setIsSerial(false))))
        @Suppress("UNCHECKED_CAST")
        methodChannel.methodChannel.setMethodCallHandler { call, result ->
            try {
                val args = call.arguments as Map<String, Any>?
                when (call.method) {
                    "clearCallingIdentity" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val identity = clearCallingIdentity()
                            val id = RegistrableCallingIdentity.register(identity)
                            result.success(id)
                        } else {
                            result.success(null)
                        }
                    }
                    "getCallingAttributionTag" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            result.success(localCallingAttributionTag)
                        } else {
                            result.success(null)
                        }
                    }
                    "getCallingPackage" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                            if (localCallingPackageSecurityException == null) {
                                result.success(localCallingPackage)
                            } else {
                                result.error(
                                        "SecurityException",
                                        localCallingPackageSecurityException!!.message,
                                        localCallingPackageSecurityException!!.stackTraceToString())
                            }
                        } else {
                            result.success(null)
                        }
                    }
                    "getCallingPackageUnchecked" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            result.success(localCallingPackageUnchecked)
                        } else {
                            result.success(null)
                        }
                    }
                    "restoreCallingIdentity" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val identityId = args!!["identity"] as String
                            val identity = RegistrableCallingIdentity.unregister(identityId)
                            restoreCallingIdentity(identity!!)
                            result.success(null)
                        } else {
                            result.success(null)
                        }
                    }
                    else -> result.notImplemented()
                }
            } catch (e : Exception) {
                methodCallFail(result, e)
            }
        }
        return true
    }

    /** Calls [MethodChannel.invokeMethod], blocking the caller thread. */
    @SuppressWarnings("WeakerAccess")
    protected fun invokeMethod(method: String, arguments: Any?): Any? {
        return methodChannel.invokeMethod(method, arguments)
    }

    /** Calls [MethodChannel.invokeMethod], not blocking the caller thread. */
    @SuppressWarnings("WeakerAccess")
    protected fun asyncInvokeMethod(method: String, arguments: Any?) {
        Handler(Looper.getMainLooper()).post {
            methodChannel.methodChannel.invokeMethod(method, arguments)
        }
    }

    override fun bulkInsert(uri: Uri, values: Array<out ContentValues>): Int {
        return invokeMethod("bulkInsert", mapOf(
                "uri" to uri,
                "values" to values))
                as Int
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        return mapToBundle(asMap(invokeMethod("call", mapOf(
                "method" to method,
                "arg" to arg,
                "extras" to extras))))
    }

    override fun call(authority: String, method: String, arg: String?, extras: Bundle?): Bundle? {
        return mapToBundle(asMap(invokeMethod("callWithAuthority", mapOf(
                "authority" to authority,
                "method" to method,
                "arg" to arg,
                "extras" to extras))))
    }

    override fun canonicalize(url: Uri): Uri? {
        return getUri(invokeMethod("canonicalize", mapOf(
                "url" to url)))
    }

    //
    // clearCallingIdentity
    //

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        return invokeMethod("delete", mapOf(
                "uri" to uri,
                "selection" to selection,
                "selectionArgs" to selectionArgs))
                as Int
    }

    override fun delete(uri: Uri, extras: Bundle?): Int {
        return invokeMethod("deleteWithExtras", mapOf(
                "uri" to uri,
                "extras" to extras))
                as Int
    }

    //
    // dump
    //
    // getCallingAttributionTag
    //
    // getCallingPackage
    //
    // getCallingPackageUnchecked
    //

    override fun getStreamTypes(uri: Uri, mimeTypeFilter: String): Array<String>? {
        return listAsArray(invokeMethod("getStreamTypes", mapOf(
                "uri" to uri,
                "mimeTypeFilter" to mimeTypeFilter)))
    }

    override fun getType(uri: Uri): String? {
        return invokeMethod("getType", mapOf(
                "uri" to uri))
                as String?
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        return getUri(invokeMethod("insert", mapOf(
                "uri" to uri,
                "values" to values)))
    }

    override fun insert(uri: Uri, values: ContentValues?, extras: Bundle?): Uri? {
        return getUri(invokeMethod("insertWithExtras", mapOf(
                "uri" to uri,
                "values" to values,
                "extras" to extras)))
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private var localCallingAttributionTag :String? = null
    @RequiresApi(Build.VERSION_CODES.KITKAT)
    private var localCallingPackage :String? = null
    @RequiresApi(Build.VERSION_CODES.KITKAT)
    private var localCallingPackageSecurityException : SecurityException? = null
    @RequiresApi(Build.VERSION_CODES.R)
    private var localCallingPackageUnchecked :String? = null

    override fun onCallingPackageChanged() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            localCallingAttributionTag = callingAttributionTag
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            try {
                localCallingPackageSecurityException = null
                localCallingPackage = callingPackage
            } catch (e : SecurityException) {
                // Pass to local variable to rethrow when it's actually accessed
                localCallingPackageSecurityException = e
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            localCallingPackageUnchecked = callingPackageUnchecked
        }
        invokeMethod("onCallingPackageChanged", null)
    }

    override fun onLowMemory() {
        asyncInvokeMethod("onLowMemory", null)
    }

    override fun onTrimMemory(level: Int) {
        asyncInvokeMethod("onTrimMemory", mapOf(
                "level" to level))
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val path = invokeMethod("openFile", mapOf(
                "uri" to uri,
                "mode" to mode))
                as String?
        return openFileFromPath(path, mode)
    }

    override fun openFile(uri: Uri, mode: String, cancellationSignal: CancellationSignal?): ParcelFileDescriptor? {
        var interoperableSignal: InteroperableCancellationSignal? = null
        cancellationSignal?.let {
            interoperableSignal = InteroperableCancellationSignal(engine.dartExecutor.binaryMessenger)
            it.setOnCancelListener {
                interoperableSignal!!.signal!!.cancel()
            }
        }
        try {
            val path = invokeMethod("openFileWithSignal", mapOf(
                    "uri" to uri,
                    "mode" to mode,
                    "cancellationSignal" to interoperableSignal?.id))
                    as String?
            return openFileFromPath(path, mode)
        } finally {
            interoperableSignal?.destroy()
        }
    }

    private fun openFileFromPath(path: String?, mode: String): ParcelFileDescriptor {
        val file: File
        if (path != null) {
            file = File(path)
        } else {
            throw FileNotFoundException(path)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
        } else {
            ParcelFileDescriptor.open(file, translateModeStringToPosix(mode))
        }
    }

    /**
     * Copies the logic from [ParcelFileDescriptor.parseMode] to use
     * it below KITKAT.
     */
    @SuppressWarnings("WeakerAccess")
    protected fun translateModeStringToPosix(mode: String): Int {
        // Quick check for invalid chars
        for (element in mode) {
            when (element) {
                'r', 'w', 't', 'a' -> {
                }
                else -> throw IllegalArgumentException("Bad mode: $mode")
            }
        }
        var res: Int
        res = when {
            mode.startsWith("rw") -> {
                ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
            }
            mode.startsWith("w") -> {
                ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE
            }
            mode.startsWith("r") -> {
                ParcelFileDescriptor.MODE_READ_ONLY
            }
            else -> {
                throw IllegalArgumentException("Bad mode: $mode")
            }
        }
        if (mode.indexOf('t') != -1) {
            res = res or ParcelFileDescriptor.MODE_TRUNCATE
        }
        if (mode.indexOf('a') != -1) {
            res = res or ParcelFileDescriptor.MODE_APPEND
        }
        return res
    }

    private fun matrixCursorFromMap(map: Map<String, Any?>): DataMatrixCursor {
        val payload = asMap(map["payload"])!!
        val notificationUris = getUris(map["notificationUris"])
        val extras = mapToBundle(asMap(map["extras"]))

        val columnNames = listAsArray<String>(payload["columnNames"])
        val data = listAsArray<Any?>(payload["data"])
        val rowCount = payload["rowCount"] as Int

        val cursor = DataMatrixCursor(columnNames, data, rowCount)
        notificationUris?.let {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.setNotificationUris(context!!.contentResolver, it)
                } else {
                    if (it.isNotEmpty()) {
                        cursor.setNotificationUri(context!!.contentResolver, it.first())
                    }
                }
            } catch ( e: SecurityException) {
                Log.i(
                    "AndroidContentProvider",
                    "It's likely that you are providing an invalid URI. " +
                    "Either don't pass `notificationUris` parameter, or make it valid. " +
                    "Typically it should match with the URI of some resource " +
                    "of your content provider, that matches this Cursor's content")
                throw e
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            cursor.extras = extras
        } // else silently no-op
        return cursor
    }

    @Suppress("UNCHECKED_CAST")
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val result = asMap(invokeMethod("query", mapOf(
                "uri" to uri,
                "projection" to projection,
                "selection" to selection,
                "selectionArgs" to selectionArgs,
                "sortOrder" to sortOrder)))
                ?: return null
        return matrixCursorFromMap(result)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?, cancellationSignal: CancellationSignal?): Cursor? {
        var interoperableSignal: InteroperableCancellationSignal? = null
        cancellationSignal?.let {
            interoperableSignal = InteroperableCancellationSignal(engine.dartExecutor.binaryMessenger)
            it.setOnCancelListener {
                interoperableSignal!!.signal!!.cancel()
            }
        }
        try {
            val result = asMap(invokeMethod("queryWithSignal", mapOf(
                    "uri" to uri,
                    "projection" to projection,
                    "selection" to selection,
                    "selectionArgs" to selectionArgs,
                    "sortOrder" to sortOrder,
                    "cancellationSignal" to interoperableSignal?.id)))
                    ?: return null
            return matrixCursorFromMap(result)
        } finally {
            interoperableSignal?.destroy()
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, queryArgs: Bundle?, cancellationSignal: CancellationSignal?): Cursor? {
        var interoperableSignal: InteroperableCancellationSignal? = null
        cancellationSignal?.let {
            interoperableSignal = InteroperableCancellationSignal(engine.dartExecutor.binaryMessenger)
            it.setOnCancelListener {
                interoperableSignal!!.signal!!.cancel()
            }
        }
        try {
            val result = asMap(invokeMethod("queryWithExtras", mapOf(
                    "uri" to uri,
                    "projection" to projection,
                    "queryArgs" to queryArgs,
                    "cancellationSignal" to interoperableSignal?.id)))
                    ?: return null
            return matrixCursorFromMap(result)
        } finally {
            interoperableSignal?.destroy()
        }
    }

    override fun refresh(uri: Uri, extras: Bundle?, cancellationSignal: CancellationSignal?): Boolean {
        var interoperableSignal: InteroperableCancellationSignal? = null
        cancellationSignal?.let {
            interoperableSignal = InteroperableCancellationSignal(engine.dartExecutor.binaryMessenger)
            it.setOnCancelListener {
                interoperableSignal!!.signal!!.cancel()
            }
        }
        try {
            return invokeMethod("refresh", mapOf(
                    "uri" to uri,
                    "extras" to extras,
                    "cancellationSignal" to interoperableSignal?.id))
                    as Boolean
        } finally {
            interoperableSignal?.destroy()
        }
    }

    //
    // restoreCallingIdentity
    //
    // shutdown
    //

    override fun uncanonicalize(url: Uri): Uri? {
        return getUri(invokeMethod("uncanonicalize", mapOf(
                "url" to url)))
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        return invokeMethod("update", mapOf(
                "uri" to uri,
                "values" to values,
                "selection" to selection,
                "selectionArgs" to selectionArgs))
                as Int
    }

    override fun update(uri: Uri, values: ContentValues?, extras: Bundle?): Int {
        return invokeMethod("updateWithExtras", mapOf(
                "uri" to uri,
                "values" to values,
                "extras" to extras))
                as Int
    }
}
