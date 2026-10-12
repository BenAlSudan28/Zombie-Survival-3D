package com.mussab.zombiesurvival

import android.content.Context
import android.opengl.GLSurfaceView
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.MotionEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Random
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class GameSurface(context: Context) : GLSurfaceView(context) {

    private val game = ZombieRenderer(context)

    init {
        setEGLContextClientVersion(2)
        setRenderer(game)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x / width.coerceAtLeast(1)
        val y = event.y / height.coerceAtLeast(1)

        when (event.action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                game.control(x, y, true)
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                game.control(x, y, false)
            }
        }

        return true
    }
}

private class ZombieRenderer(
    private val context: Context
) : GLSurfaceView.Renderer {

    private var program = 0
    private var positionHandle = 0
    private var colorHandle = 0
    private var matrixHandle = 0

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)

    private var playerX = 0f
    private var playerZ = 0f
    private var health = 100
    private var score = 0
    private var elapsed = 0f
    private var lastTime = System.nanoTime()

    private var moveLeft = false
    private var moveRight = false
    private var firing = false
    private var gameOver = false

    private data class Zombie(
        var x: Float,
        var z: Float,
        var alive: Boolean = true
    )

    private val zombies = mutableListOf<Zombie>()
    private val random = Random()

    private val vertexShader = """
        uniform mat4 uMVP;
        attribute vec4 aPosition;
        void main() {
            gl_Position = uMVP * aPosition;
        }
    """.trimIndent()

    private val fragmentShader = """
        precision mediump float;
        uniform vec4 uColor;
        void main() {
            gl_FragColor = uColor;
        }
    """.trimIndent()

    private val cubeVertices = floatArrayOf(
        -0.5f,-0.5f, 0.5f,
         0.5f,-0.5f, 0.5f,
         0.5f, 0.5f, 0.5f,
        -0.5f, 0.5f, 0.5f,

        -0.5f,-0.5f,-0.5f,
        -0.5f, 0.5f,-0.5f,
         0.5f, 0.5f,-0.5f,
         0.5f,-0.5f,-0.5f,

        -0.5f, 0.5f,-0.5f,
        -0.5f, 0.5f, 0.5f,
         0.5f, 0.5f, 0.5f,
         0.5f, 0.5f,-0.5f,

        -0.5f,-0.5f,-0.5f,
         0.5f,-0.5f,-0.5f,
         0.5f,-0.5f, 0.5f,
        -0.5f,-0.5f, 0.5f,

         0.5f,-0.5f,-0.5f,
         0.5f, 0.5f,-0.5f,
         0.5f, 0.5f, 0.5f,
         0.5f,-0.5f, 0.5f,

        -0.5f,-0.5f,-0.5f,
        -0.5f,-0.5f, 0.5f,
        -0.5f, 0.5f, 0.5f,
        -0.5f, 0.5f,-0.5f
    )

    private val indices = shortArrayOf(
        0,1,2, 0,2,3,
        4,5,6, 4,6,7,
        8,9,10, 8,10,11,
        12,13,14, 12,14,15,
        16,17,18, 16,18,19,
        20,21,22, 20,22,23
    )

    private val vertexBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(cubeVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(cubeVertices)
                position(0)
            }

    private val indexBuffer =
        ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .apply {
                put(indices)
                position(0)
            }

    override fun onSurfaceCreated(
        gl: GL10?,
        config: EGLConfig?
    ) {
        GLES20.glClearColor(0.025f, 0.035f, 0.045f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        val vs = compileShader(
            GLES20.GL_VERTEX_SHADER,
            vertexShader
        )

        val fs = compileShader(
            GLES20.GL_FRAGMENT_SHADER,
            fragmentShader
        )

        program = GLES20.glCreateProgram()

        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)

        positionHandle =
            GLES20.glGetAttribLocation(program, "aPosition")

        colorHandle =
            GLES20.glGetUniformLocation(program, "uColor")

        matrixHandle =
            GLES20.glGetUniformLocation(program, "uMVP")

        zombies.clear()

        repeat(8) {
            spawnZombie()
        }
    }

    override fun onSurfaceChanged(
        gl: GL10?,
        width: Int,
        height: Int
    ) {
        GLES20.glViewport(0, 0, width, height)

        Matrix.perspectiveM(
            projection,
            0,
            65f,
            width.toFloat() / height.coerceAtLeast(1),
            0.1f,
            100f
        )
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(
            GLES20.GL_COLOR_BUFFER_BIT or
                GLES20.GL_DEPTH_BUFFER_BIT
        )

        if (gameOver) {
            drawGameOverScene()
            return
        }

        val now = System.nanoTime()
        val dt = ((now - lastTime) / 1_000_000_000f)
            .coerceIn(0f, 0.05f)

        lastTime = now
        elapsed += dt

        if (moveLeft) playerX -= dt * 3f
        if (moveRight) playerX += dt * 3f

        playerX = playerX.coerceIn(-4f, 4f)

        Matrix.setLookAtM(
            view, 0,
            playerX, 2.2f, 5.5f,
            playerX, 0.7f, -4f,
            0f, 1f, 0f
        )

        GLES20.glUseProgram(program)

        drawCube(
            playerX, 0.8f, 0f,
            0.5f, 1.5f, 0.4f,
            0.2f, 0.65f, 1f
        )

        drawCube(
            playerX, 1.75f, 0f,
            0.65f, 0.65f, 0.65f,
            0.85f, 0.72f, 0.55f
        )

        drawCube(
            playerX, 0.12f, -7f,
            12f, 0.1f, 25f,
            0.12f, 0.14f, 0.15f
        )

        for (i in -5..5) {
            drawCube(
                i.toFloat(), 0.03f, -7f,
                0.025f, 0.02f, 25f,
                0.3f, 0.32f, 0.3f
            )
        }

        updateZombies(dt)

        if (firing && elapsed > lastShot + 0.35f) {
            shoot()
            lastShot = elapsed
        }
    }

    private var lastShot = 0f

    private fun updateZombies(dt: Float) {
        val iterator = zombies.iterator()

        while (iterator.hasNext()) {
            val z = iterator.next()

            if (!z.alive) {
                iterator.remove()
                continue
            }

            val dx = playerX - z.x
            val dz = playerZ - z.z
            val distance = sqrt(dx * dx + dz * dz)

            if (distance > 0.001f) {
                z.x += dx / distance * dt * 0.65f
                z.z += dz / distance * dt * 0.65f
            }

            if (distance < 0.9f) {
                health -= 1

                if (health <= 0) {
                    health = 0
                    gameOver = true
                }
            }

            drawCube(
                z.x, 0.8f, z.z,
                0.55f, 1.5f, 0.45f,
                0.18f, 0.48f, 0.22f
            )

            drawCube(
                z.x, 1.7f, z.z,
                0.62f, 0.62f, 0.62f,
                0.35f, 0.65f, 0.28f
            )

            drawCube(
                z.x, 1.75f, z.z + 0.3f,
                0.12f, 0.09f, 0.05f,
                1f, 0.1f, 0.08f
            )
        }

        if (zombies.size < 8 && random.nextFloat() < dt * 0.6f) {
            spawnZombie()
        }
    }

    private fun shoot() {
        val target = zombies
            .filter { it.alive }
            .minByOrNull {
                val dx = it.x - playerX
                val dz = it.z - playerZ
                dx * dx + dz * dz
            }

        if (target != null) {
            val dx = target.x - playerX
            val dz = target.z - playerZ

            if (kotlin.math.abs(dx) < 2.2f && dz < 0f) {
                target.alive = false
                score += 10
            }
        }
    }

    private fun spawnZombie() {
        zombies.add(
            Zombie(
                x = playerX + random.nextFloat() * 8f - 4f,
                z = -8f - random.nextFloat() * 8f
            )
        )
    }

    fun control(x: Float, y: Float, pressed: Boolean) {
        if (gameOver) {
            if (pressed) {
                playerX = 0f
                playerZ = 0f
                health = 100
                score = 0
                elapsed = 0f
                gameOver = false
                zombies.clear()

                repeat(8) {
                    spawnZombie()
                }
            }
            return
        }

        if (y > 0.55f) {
            moveLeft = pressed && x < 0.33f
            moveRight = pressed && x > 0.66f
        }

        if (x > 0.72f && y < 0.55f) {
            firing = pressed
        }
    }

    private fun drawCube(
        x: Float,
        y: Float,
        z: Float,
        sx: Float,
        sy: Float,
        sz: Float,
        r: Float,
        g: Float,
        b: Float
    ) {
        Matrix.setIdentityM(model, 0)

        Matrix.translateM(model, 0, x, y, z)
        Matrix.scaleM(model, 0, sx, sy, sz)

        val pv = FloatArray(16)

        Matrix.multiplyMM(pv, 0, projection, 0, view, 0)
        Matrix.multiplyMM(mvp, 0, pv, 0, model, 0)

        GLES20.glUniformMatrix4fv(
            matrixHandle, 1, false, mvp, 0
        )

        GLES20.glUniform4f(colorHandle, r, g, b, 1f)

        GLES20.glEnableVertexAttribArray(positionHandle)

        GLES20.glVertexAttribPointer(
            positionHandle,
            3,
            GLES20.GL_FLOAT,
            false,
            12,
            vertexBuffer
        )

        GLES20.glDrawElements(
            GLES20.GL_TRIANGLES,
            indices.size,
            GLES20.GL_UNSIGNED_SHORT,
            indexBuffer
        )

        GLES20.glDisableVertexAttribArray(positionHandle)
    }

    private fun drawGameOverScene() {
        GLES20.glClearColor(0.12f, 0.01f, 0.02f, 1f)
        GLES20.glClear(
            GLES20.GL_COLOR_BUFFER_BIT or
                GLES20.GL_DEPTH_BUFFER_BIT
        )
    }

    private fun compileShader(
        type: Int,
        source: String
    ): Int {
        val shader = GLES20.glCreateShader(type)

        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val status = IntArray(1)

        GLES20.glGetShaderiv(
            shader,
            GLES20.GL_COMPILE_STATUS,
            status,
            0
        )

        if (status[0] == 0) {
            val error = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compilation failed: $error")
        }

        return shader
    }
}
