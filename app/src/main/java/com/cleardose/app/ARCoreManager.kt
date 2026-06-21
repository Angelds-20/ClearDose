package com.cleardose.app

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

data class RealWorldPoint(val x: Float, val y: Float, val z: Float)
data class ScreenPoint(val x: Float, val y: Float)

class ARCoreManager(private val context: Context) : GLSurfaceView.Renderer {
    var session: Session? = null
    var lastFrame: Frame? = null
    private var textureId = -1
    private var program = 0
    private var viewportWidth = 1f
    private var viewportHeight = 1f

    private val quadVertices: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }
    private val texInput: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))
            position(0)
        }
    private val texVertices: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun resume() {
        try {
            if (session == null) {
                session = Session(context)
                val config = Config(session)
                config.focusMode = Config.FocusMode.AUTO
                config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                session!!.configure(config)
            }
            session?.resume()
        } catch (e: Exception) { Log.e("ARCore", "Resume Error: ${e.message}") }
    }

    fun pause() { session?.pause() }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, "attribute vec4 aPos; attribute vec2 aTex; varying vec2 vTex; void main() { gl_Position = aPos; vTex = aTex; }")
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 vTex; uniform samplerExternalOES sTex; void main() { gl_FragColor = texture2D(sTex, vTex); }")
        program = GLES20.glCreateProgram().apply {
            GLES20.glAttachShader(this, vertexShader)
            GLES20.glAttachShader(this, fragmentShader)
            GLES20.glLinkProgram(this)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.toFloat()
        viewportHeight = height.toFloat()
        GLES20.glViewport(0, 0, width, height)
        session?.setDisplayGeometry(0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val session = session ?: return
        try {
            session.setCameraTextureName(textureId)
            val frame = session.update()
            lastFrame = frame
            texInput.position(0); texVertices.position(0)
            frame.transformDisplayUvCoords(texInput, texVertices)
            GLES20.glUseProgram(program)
            
            // BIND THE TEXTURE
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            val samplerH = GLES20.glGetUniformLocation(program, "sTex")
            GLES20.glUniform1i(samplerH, 0)

            val posH = GLES20.glGetAttribLocation(program, "aPos")
            val texH = GLES20.glGetAttribLocation(program, "aTex")
            GLES20.glEnableVertexAttribArray(posH)
            GLES20.glVertexAttribPointer(posH, 2, GLES20.GL_FLOAT, false, 0, quadVertices)
            GLES20.glEnableVertexAttribArray(texH)
            GLES20.glVertexAttribPointer(texH, 2, GLES20.GL_FLOAT, false, 0, texVertices)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: Exception) {}
    }

    private fun loadShader(type: Int, code: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, code); GLES20.glCompileShader(it) }

    fun hitTestCenter(): RealWorldPoint? {
        val frame = lastFrame ?: return null
        if (frame.camera.trackingState != TrackingState.TRACKING) return null
        val hits = frame.hitTest(viewportWidth / 2f, viewportHeight / 2f)
        val hit = hits.firstOrNull() ?: return null
        return RealWorldPoint(hit.hitPose.tx(), hit.hitPose.ty(), hit.hitPose.tz())
    }
}
