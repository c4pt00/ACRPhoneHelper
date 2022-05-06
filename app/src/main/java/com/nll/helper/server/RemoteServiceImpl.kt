package com.nll.helper.server

import android.content.Context
import android.os.IBinder
import android.util.Log
import com.nll.helper.bridge.AccessibilityServiceBridge
import com.nll.helper.bridge.RecorderBridge

/**
 *
 * Server files should be within app-recorder and copied to acr module app-helper-client.
 *
 * Spent a day trying to move client files to its own module like server
 *
 * Had to include server files and consequently included manifest declarations for the server
 *
 * Spend 3 hour figuring out ACR PHone was actually connection to itself because server files were packaged!!
 *
 */
class RemoteServiceImpl(private val context: Context) : IRemoteService {
    private val logTag = "CR_RemoteServiceImpl (${Integer.toHexString(System.identityHashCode(this))})"

    private val accessibilityServiceBridge: IAccessibilityServiceBridge = AccessibilityServiceBridge()
    private val recorderBridge: IRecorderBridge = RecorderBridge()

    private var serverRecordingState: ServerRecordingState = ServerRecordingState.Stopped
    private val serverRecorderListener = object : ServerRecorderListener {
        override fun onRecordingStateChange(newState: ServerRecordingState) {
            Log.i(logTag, "onRecordingStateChange() -> newState: $newState")

            serverRecordingState = newState
            listeners.forEach { listener ->
                try {
                    Log.i(logTag, "onRecordingStateChange() -> listener: $listener")
                    listener.onRecordingStateChange(newState.asResponseCode())
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

    }

    override suspend fun startRecording(
        encoder: Int,
        recordingFile: String,
        audioChannels: Int,
        encodingBitrate: Int,
        audioSamplingRate: Int,
        audioSource: Int,
        mediaRecorderOutputFormat: Int,
        mediaRecorderAudioEncoder: Int,
        recordingGain: Int
    ): Int {

        val result = if (accessibilityServiceBridge.isHelperServiceEnabled(context)) {
            try {
                recorderBridge.startRecording(
                    context = context.applicationContext,
                    encoder = encoder,
                    recordingFile = recordingFile,
                    audioChannels = audioChannels,
                    encodingBitrate = encodingBitrate,
                    audioSamplingRate = audioSamplingRate,
                    audioSource = audioSource,
                    mediaRecorderOutputFormat = mediaRecorderOutputFormat,
                    mediaRecorderAudioEncoder = mediaRecorderAudioEncoder,
                    recordingGain = recordingGain,
                    serverRecorderListener = serverRecorderListener
                )
            } catch (e: Exception) {
                e.printStackTrace()
                RemoteResponseCodes.RECORDING_STOPPED
            }

        } else {
            Log.i(logTag, "startRecording() -> isHelperServiceEnabled false. Return HELPER_IS_NOT_RUNNING (${RemoteResponseCodes.HELPER_IS_NOT_RUNNING})")
            RemoteResponseCodes.HELPER_IS_NOT_RUNNING
        }
        Log.i(logTag, "startRecording() -> result: $result")
        return result
    }

    private fun internalStopRecordingAndCleanup(){
        Log.i(logTag, "internalStopRecordingAndCleanup()")
        try {
            recorderBridge.stopRecording()
        } catch (e: Exception) {
            e.printStackTrace()
            /*
                Set state to stopped if there was a crash
                We do not use try catch at recorderBridge because we do not have access to serverRecordingState there
             */
            serverRecordingState = ServerRecordingState.Stopped
        }
    }
    override suspend fun stopRecording() {
        Log.i(logTag, "stopRecording()")
        internalStopRecordingAndCleanup()
    }

    override suspend fun pauseRecording() {
        Log.i(logTag, "pauseRecording()")
        try {
            recorderBridge.pauseRecording()
        } catch (e: Exception) {
            e.printStackTrace()

            Log.i(logTag, "Crash! Call internalStopRecordingAndCleanup()")
            internalStopRecordingAndCleanup()
        }


    }

    override suspend fun resumeRecording() {
        Log.i(logTag, "resumeRecording()")
        try {
            recorderBridge.resumeRecording()

        } catch (e: Exception) {
            e.printStackTrace()

            Log.i(logTag, "Crash! Call internalStopRecordingAndCleanup()")
            internalStopRecordingAndCleanup()
        }


    }

    /**
     * Listen to client process death to re init and ask to connect back
     */
    private lateinit var clientBinder: IBinder
    override suspend fun registerClientProcessDeath(clientDeathListener: IBinder) {
        Log.i(logTag, "registerClientProcessDeath()")
        clientBinder = clientDeathListener
        //Create new anonymous class of IBinder.DeathRecipient()
        clientDeathListener.linkToDeath(object : IBinder.DeathRecipient {
            override fun binderDied() {
                Log.i(logTag, "registerClientProcessDeath() -> Client died")
                try {
                    Log.i(logTag, "onUnbind() -> Calling internalStopRecordingAndCleanup() just to make sure stop recording when client dies")
                    internalStopRecordingAndCleanup()
                    Log.i(logTag, "onUnbind() -> Calling stopRecording() asking client to conenct again")
                    ClientContentProviderHelper.askToClientToConnect(context)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                //Unregister from client death recipient
                clientBinder.unlinkToDeath(this, 0)
            }
        }, 0)
    }

    private val listeners = mutableListOf<IRemoteServiceListener>()
    override fun registerListener(listener: IRemoteServiceListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    override fun unRegisterListener(listener: IRemoteServiceListener) {
        if (listeners.contains(listener)) {
            listeners.remove(listener)
        }
    }


}