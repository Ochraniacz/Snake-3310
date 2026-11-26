package com.damian.snake3310

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val gameView = Snake3310View(this)
        setContentView(gameView)
    }

    private enum class Direction(val dx: Int, val dy: Int) {
        UP(0, -1),
        DOWN(0, 1),
        LEFT(-1, 0),
        RIGHT(1, 0);

        fun isOpposite(other: Direction): Boolean {
            return dx + other.dx == 0 && dy + other.dy == 0
        }
    }

    private enum class GameState {
        START,
        PLAYING,
        PAUSED,
        GAME_OVER
    }

    private data class Cell(val x: Int, val y: Int)

    private class Snake3310View(context: Context) : View(context) {

        // Paleta klasycznego ekranu LCD Nokii 3310
        private val lcdBgColor = Color.rgb(155, 188, 15)      // #9BBC0F
        private val lcdDarkColor = Color.rgb(15, 56, 15)      // #0F380F
        private val lcdMidColor = Color.rgb(48, 98, 48)       // #306230
        private val lcdLightColor = Color.rgb(139, 172, 15)   // #8BAC0F

        private val gridCols = 20
        private val gridRows = 22

        private val prefs = context.getSharedPreferences("snake_3310_prefs", Context.MODE_PRIVATE)
        private var bestScore = prefs.getInt("best_score", 0)
        private var soundEnabled = prefs.getBoolean("sound_enabled", true)

        private var gameState = GameState.START
        private var snake = ArrayDeque<Cell>()
        private var currentDir = Direction.RIGHT
        private var nextDir = Direction.RIGHT
        private var food = Cell(14, 10)

        // Bonusowy robaczek pojawiający się co kilka zjedzonych punktów
        private var bonusFood: Cell? = null
        private var bonusExpiresAtMs = 0L
        private val bonusDurationMs = 7_500L
        private var foodsEatenSinceBonus = 0

        private var score = 0
        private var stepIntervalMs = 155L
        private var lastStepMs = 0L
        private var blinkTick = false

        // Sterowanie dotykowe (swipe + przyciski ekranowe)
        private var touchDownX = 0f
        private var touchDownY = 0f
        private var swipeTriggered = false

        private val boardRect = RectF()
        private val pauseBtnRect = RectF()
        private val soundBtnRect = RectF()
        private val actionBtnRect = RectF()
        private val btnUpRect = RectF()
        private val btnDownRect = RectF()
        private val btnLeftRect = RectF()
        private val btnRightRect = RectF()
        private val cellRect = RectF()

        private val bgPaint = Paint().apply { color = lcdBgColor }
        private val boardFillPaint = Paint().apply { color = lcdLightColor }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lcdDarkColor
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        private val gridDotPaint = Paint().apply {
            color = Color.argb(38, 15, 56, 15)
        }
        private val snakeHeadPaint = Paint().apply { color = lcdDarkColor }
        private val snakeBodyPaint = Paint().apply { color = lcdMidColor }
        private val foodPaint = Paint().apply { color = lcdDarkColor }
        private val btnFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lcdMidColor
            style = Paint.Style.FILL
        }
        private val btnPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lcdDarkColor
            style = Paint.Style.FILL
        }
        private val btnOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lcdDarkColor
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        private val btnLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lcdBgColor
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lcdDarkColor
            typeface = Typeface.MONOSPACE
            isFakeBoldText = true
        }
        private val overlayBgPaint = Paint().apply {
            color = Color.argb(215, 155, 188, 15)
        }
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lcdDarkColor
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        private val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lcdDarkColor
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
        }

        private var pressedDirection: Direction? = null
        private var actionPressed = false

        private val loopRunnable = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                if (gameState == GameState.PLAYING) {
                    if (bonusFood != null && now >= bonusExpiresAtMs) {
                        bonusFood = null
                    }
                    if (now - lastStepMs >= stepIntervalMs) {
                        lastStepMs = now
                        blinkTick = !blinkTick
                        stepGame(now)
                    }
                }
                invalidate()
                postDelayed(this, 16L)
            }
        }

        init {
            resetGame(GameState.START)
            post(loopRunnable)
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(loopRunnable)
            super.onDetachedFromWindow()
        }

        private fun resetGame(targetState: GameState) {
            snake.clear()
            val startY = gridRows / 2
            snake.addLast(Cell(6, startY))
            snake.addLast(Cell(5, startY))
            snake.addLast(Cell(4, startY))

            currentDir = Direction.RIGHT
            nextDir = Direction.RIGHT
            score = 0
            foodsEatenSinceBonus = 0
            bonusFood = null
            bonusExpiresAtMs = 0L
            stepIntervalMs = 155L
            lastStepMs = System.currentTimeMillis()
            gameState = targetState
            spawnFood()
        }

        private fun spawnFood() {
            val occupied = snake.toHashSet()
            bonusFood?.let { occupied.add(it) }

            val freeCells = ArrayList<Cell>(gridCols * gridRows)
            for (y in 0 until gridRows) {
                for (x in 0 until gridCols) {
                    val c = Cell(x, y)
                    if (!occupied.contains(c)) {
                        freeCells.add(c)
                    }
                }
            }
            if (freeCells.isNotEmpty()) {
                food = freeCells[Random.nextInt(freeCells.size)]
            }
        }

        private fun maybeSpawnBonusFood(now: Long) {
            if (bonusFood != null) return
            if (foodsEatenSinceBonus >= 5) {
                foodsEatenSinceBonus = 0
                val occupied = snake.toHashSet()
                occupied.add(food)

                val freeCells = ArrayList<Cell>(gridCols * gridRows)
                for (y in 0 until gridRows) {
                    for (x in 0 until gridCols) {
                        val c = Cell(x, y)
                        if (!occupied.contains(c)) {
                            freeCells.add(c)
                        }
                    }
                }
                if (freeCells.isNotEmpty()) {
                    bonusFood = freeCells[Random.nextInt(freeCells.size)]
                    bonusExpiresAtMs = now + bonusDurationMs
                }
            }
        }

        private fun stepGame(now: Long) {
            if (!nextDir.isOpposite(currentDir)) {
                currentDir = nextDir
            }

            val head = snake.first()
            val newHead = Cell(head.x + currentDir.dx, head.y + currentDir.dy)

            // Klasyczne ściany Nokii 3310: uderzenie w ramkę = Game Over
            if (newHead.x < 0 || newHead.x >= gridCols || newHead.y < 0 || newHead.y >= gridRows) {
                triggerGameOver()
                return
            }

            val willEatFood = newHead == food
            val willEatBonus = bonusFood != null && newHead == bonusFood

            // Sprawdzenie kolizji z własnym ciałem (ostatni segment zniknie, jeśli nie jemy)
            val bodyToCheck = if (willEatFood || willEatBonus) {
                snake
            } else {
                snake.dropLast(1)
            }
            if (bodyToCheck.contains(newHead)) {
                triggerGameOver()
                return
            }

            snake.addFirst(newHead)

            if (willEatFood) {
                score += 10
                foodsEatenSinceBonus += 1
                updateBestScoreIfNeeded()
                stepIntervalMs = max(72L, 155L - (score / 20) * 6L)
                vibrateShort(25L)
                playBeep(880.0, 45)
                spawnFood()
                maybeSpawnBonusFood(now)
            } else if (willEatBonus) {
                val remainingMs = max(0L, bonusExpiresAtMs - now)
                val bonusPoints = 20 + (remainingMs / 500L).toInt()
                score += bonusPoints
                updateBestScoreIfNeeded()
                bonusFood = null
                vibrateShort(45L)
                playBeep(1180.0, 75)
            } else {
                snake.removeLast()
            }
        }

        private fun updateBestScoreIfNeeded() {
            if (score > bestScore) {
                bestScore = score
                prefs.edit().putInt("best_score", bestScore).apply()
            }
        }

        private fun triggerGameOver() {
            gameState = GameState.GAME_OVER
            bonusFood = null
            updateBestScoreIfNeeded()
            vibrateShort(140L)
            playBeep(220.0, 180)
        }

        private fun queueDirection(newDir: Direction) {
            if (gameState != GameState.PLAYING) return
            val referenceDir = if (nextDir != currentDir) nextDir else currentDir
            if (!newDir.isOpposite(referenceDir)) {
                nextDir = newDir
            }
        }

        private fun vibrateShort(durationMs: Long) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    vm?.defaultVibrator?.vibrate(
                        VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    @Suppress("DEPRECATION")
                    vib?.vibrate(durationMs)
                }
            } catch (_: Exception) {
            }
        }

        private fun playBeep(freqHz: Double, durationMs: Int) {
            if (!soundEnabled) return
            Thread {
                try {
                    val sampleRate = 8000
                    val numSamples = max(1, (sampleRate * durationMs) / 1000)
                    val samples = ShortArray(numSamples)
                    for (i in 0 until numSamples) {
                        val angle = 2.0 * PI * i * freqHz / sampleRate
                        samples[i] = (sin(angle) * Short.MAX_VALUE * 0.25).toInt().toShort()
                    }
                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    val format = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                    val track = AudioTrack.Builder()
                        .setAudioAttributes(attrs)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(samples.size * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build()
                    track.write(samples, 0, samples.size)
                    track.play()
                    Thread.sleep((durationMs + 25).toLong())
                    track.release()
                } catch (_: Exception) {
                }
            }.start()
        }

        private fun computeLayout(w: Float, h: Float) {
            val pad = w * 0.04f
            val topHudHeight = h * 0.09f
            val dpadAreaHeight = h * 0.27f

            pauseBtnRect.set(w - pad - w * 0.20f, pad * 0.7f, w - pad, pad * 0.7f + topHudHeight * 0.65f)
            soundBtnRect.set(
                pauseBtnRect.left - pad * 0.6f - w * 0.16f,
                pauseBtnRect.top,
                pauseBtnRect.left - pad * 0.6f,
                pauseBtnRect.bottom
            )

            val boardTop = topHudHeight + pad * 0.5f
            val boardBottom = h - dpadAreaHeight - pad * 0.6f
            val availW = w - pad * 2f
            val availH = max(120f, boardBottom - boardTop)

            val cellSize = min(availW / gridCols, availH / gridRows)
            val actualBoardW = cellSize * gridCols
            val actualBoardH = cellSize * gridRows
            val boardLeft = (w - actualBoardW) / 2f
            val boardCenteredTop = boardTop + (availH - actualBoardH) / 2f

            boardRect.set(boardLeft, boardCenteredTop, boardLeft + actualBoardW, boardCenteredTop + actualBoardH)

            // Przycisk główny akcji (START / PAUZA / ZAGRAJ PONOWNIE) na środku nakładki lub na dole
            val actionW = actualBoardW * 0.56f
            val actionH = min(h * 0.062f, 68f)
            val actionTop = boardRect.centerY() + actualBoardH * 0.14f
            actionBtnRect.set(
                w / 2f - actionW / 2f,
                actionTop,
                w / 2f + actionW / 2f,
                actionTop + actionH
            )

            // Klasyczny krzyżak D-Pad na dole ekranu
            val dpadTop = boardRect.bottom + pad * 0.6f
            val keyW = w * 0.24f
            val keyH = min(h * 0.078f, 84f)
            val centerX = w / 2f
            val gap = keyW * 0.14f

            btnUpRect.set(centerX - keyW / 2f, dpadTop, centerX + keyW / 2f, dpadTop + keyH)
            btnDownRect.set(
                centerX - keyW / 2f,
                dpadTop + keyH + gap * 2f,
                centerX + keyW / 2f,
                dpadTop + keyH * 2f + gap * 2f
            )
            btnLeftRect.set(
                centerX - keyW / 2f - keyW - gap,
                dpadTop + keyH * 0.5f + gap,
                centerX - keyW / 2f - gap,
                dpadTop + keyH * 1.5f + gap
            )
            btnRightRect.set(
                centerX + keyW / 2f + gap,
                dpadTop + keyH * 0.5f + gap,
                centerX + keyW / 2f + keyW + gap,
                dpadTop + keyH * 1.5f + gap
            )
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x
            val y = event.y
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return true
            computeLayout(w, h)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = x
                    touchDownY = y
                    swipeTriggered = false
                    pressedDirection = null
                    actionPressed = false

                    if (soundBtnRect.contains(x, y)) {
                        soundEnabled = !soundEnabled
                        prefs.edit().putBoolean("sound_enabled", soundEnabled).apply()
                        if (soundEnabled) playBeep(740.0, 40)
                        invalidate()
                        return true
                    }

                    if (pauseBtnRect.contains(x, y) && (gameState == GameState.PLAYING || gameState == GameState.PAUSED)) {
                        gameState = if (gameState == GameState.PLAYING) GameState.PAUSED else GameState.PLAYING
                        lastStepMs = System.currentTimeMillis()
                        invalidate()
                        return true
                    }

                    when {
                        btnUpRect.contains(x, y) -> {
                            pressedDirection = Direction.UP
                            queueDirection(Direction.UP)
                        }
                        btnDownRect.contains(x, y) -> {
                            pressedDirection = Direction.DOWN
                            queueDirection(Direction.DOWN)
                        }
                        btnLeftRect.contains(x, y) -> {
                            pressedDirection = Direction.LEFT
                            queueDirection(Direction.LEFT)
                        }
                        btnRightRect.contains(x, y) -> {
                            pressedDirection = Direction.RIGHT
                            queueDirection(Direction.RIGHT)
                        }
                        gameState != GameState.PLAYING && actionBtnRect.contains(x, y) -> {
                            actionPressed = true
                        }
                    }
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (gameState == GameState.PLAYING && !swipeTriggered && pressedDirection == null) {
                        val dx = x - touchDownX
                        val dy = y - touchDownY
                        val threshold = min(w, h) * 0.055f
                        if (max(abs(dx), abs(dy)) >= threshold) {
                            swipeTriggered = true
                            touchDownX = x
                            touchDownY = y
                            swipeTriggered = false
                            if (abs(dx) > abs(dy)) {
                                queueDirection(if (dx > 0f) Direction.RIGHT else Direction.LEFT)
                            } else {
                                queueDirection(if (dy > 0f) Direction.DOWN else Direction.UP)
                            }
                        }
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (actionPressed && actionBtnRect.contains(x, y) && gameState != GameState.PLAYING) {
                        resetGame(GameState.PLAYING)
                    }
                    pressedDirection = null
                    actionPressed = false
                    swipeTriggered = false
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            computeLayout(w, h)
            canvas.drawRect(0f, 0f, w, h, bgPaint)

            val pad = w * 0.04f
            hudPaint.textSize = (w * 0.042f).coerceIn(24f, 44f)
            val hudY = pad * 0.7f + hudPaint.textSize

            // Górny pasek wyników (WYNIK + REKORD)
            canvas.drawText("WYNIK: $score", pad, hudY, hudPaint)
            canvas.drawText("REKORD: $bestScore", pad, hudY + hudPaint.textSize * 1.15f, hudPaint)

            // Przycisk dźwięku (SFX) i pauzy
            drawSmallButton(canvas, soundBtnRect, if (soundEnabled) "SFX:ON" else "SFX:OFF", false)
            if (gameState == GameState.PLAYING || gameState == GameState.PAUSED) {
                drawSmallButton(
                    canvas,
                    pauseBtnRect,
                    if (gameState == GameState.PAUSED) "WZNÓW" else "PAUZA",
                    gameState == GameState.PAUSED
                )
            }

            // Plansza LCD
            canvas.drawRect(boardRect, boardFillPaint)

            val cellW = boardRect.width() / gridCols
            val cellH = boardRect.height() / gridRows
            val inset = min(cellW, cellH) * 0.10f

            // Subtelna siatka pikseli w tle planszy
            val dotRadius = max(1.5f, min(cellW, cellH) * 0.06f)
            for (gy in 0 until gridRows) {
                for (gx in 0 until gridCols) {
                    val cx = boardRect.left + (gx + 0.5f) * cellW
                    val cy = boardRect.top + (gy + 0.5f) * cellH
                    canvas.drawCircle(cx, cy, dotRadius, gridDotPaint)
                }
            }

            // Zwykłe jedzenie (klasyczny kwadrat)
            cellRect.set(
                boardRect.left + food.x * cellW + inset,
                boardRect.top + food.y * cellH + inset,
                boardRect.left + (food.x + 1) * cellW - inset,
                boardRect.top + (food.y + 1) * cellH - inset
            )
            canvas.drawRect(cellRect, foodPaint)

            // Bonusowy robaczek (migający)
            bonusFood?.let { bf ->
                if (blinkTick || (bonusExpiresAtMs - System.currentTimeMillis()) > 2000L) {
                    val bInset = inset * 0.4f
                    cellRect.set(
                        boardRect.left + bf.x * cellW + bInset,
                        boardRect.top + bf.y * cellH + bInset,
                        boardRect.left + (bf.x + 1) * cellW - bInset,
                        boardRect.top + (bf.y + 1) * cellH - bInset
                    )
                    canvas.drawRect(cellRect, snakeHeadPaint)
                }
            }

            // Wąż (głowa ciemniejsza, tułów nieco jaśniejszy w klimacie LCD)
            snake.forEachIndexed { index, segment ->
                val segInset = if (index == 0) inset * 0.5f else inset
                cellRect.set(
                    boardRect.left + segment.x * cellW + segInset,
                    boardRect.top + segment.y * cellH + segInset,
                    boardRect.left + (segment.x + 1) * cellW - segInset,
                    boardRect.top + (segment.y + 1) * cellH - segInset
                )
                canvas.drawRect(cellRect, if (index == 0) snakeHeadPaint else snakeBodyPaint)
            }

            // Ramka planszy
            canvas.drawRect(boardRect, borderPaint)

            // Krzyżak D-Pad na dole ekranu
            drawDpadButton(canvas, btnUpRect, "▲", pressedDirection == Direction.UP)
            drawDpadButton(canvas, btnDownRect, "▼", pressedDirection == Direction.DOWN)
            drawDpadButton(canvas, btnLeftRect, "◀", pressedDirection == Direction.LEFT)
            drawDpadButton(canvas, btnRightRect, "▶", pressedDirection == Direction.RIGHT)

            // Nakładki stanów START / PAUZA / GAME OVER
            if (gameState != GameState.PLAYING) {
                canvas.drawRect(boardRect, overlayBgPaint)
                canvas.drawRect(boardRect, borderPaint)

                titlePaint.textSize = (boardRect.width() * 0.095f).coerceIn(32f, 66f)
                infoPaint.textSize = (boardRect.width() * 0.046f).coerceIn(20f, 34f)

                val centerX = boardRect.centerX()
                val titleY = boardRect.top + boardRect.height() * 0.28f

                when (gameState) {
                    GameState.START -> {
                        canvas.drawText("SNAKE 3310", centerX, titleY, titlePaint)
                        canvas.drawText(
                            "D-PAD LUB PRZESUNIĘCIE PALCA",
                            centerX,
                            titleY + infoPaint.textSize * 1.7f,
                            infoPaint
                        )
                        canvas.drawText(
                            "ŚCIANY = KONIEC GRY",
                            centerX,
                            titleY + infoPaint.textSize * 3.0f,
                            infoPaint
                        )
                        drawActionButton(canvas, actionBtnRect, "START", actionPressed)
                    }

                    GameState.PAUSED -> {
                        canvas.drawText("PAUZA", centerX, titleY, titlePaint)
                        canvas.drawText(
                            "AKTUALNY WYNIK: $score",
                            centerX,
                            titleY + infoPaint.textSize * 1.8f,
                            infoPaint
                        )
                        drawActionButton(canvas, actionBtnRect, "GRAJ DALEJ", actionPressed)
                    }

                    GameState.GAME_OVER -> {
                        canvas.drawText("KONIEC GRY", centerX, titleY, titlePaint)
                        canvas.drawText(
                            "WYNIK: $score   REKORD: $bestScore",
                            centerX,
                            titleY + infoPaint.textSize * 1.8f,
                            infoPaint
                        )
                        drawActionButton(canvas, actionBtnRect, "OD NOWA", actionPressed)
                    }

                    GameState.PLAYING -> Unit
                }
            }
        }

        private fun drawSmallButton(canvas: Canvas, rect: RectF, label: String, active: Boolean) {
            canvas.drawRoundRect(rect, 8f, 8f, if (active) btnPressedPaint else btnFillPaint)
            canvas.drawRoundRect(rect, 8f, 8f, btnOutlinePaint)
            btnLabelPaint.textSize = (rect.height() * 0.44f).coerceIn(16f, 28f)
            val textY = rect.centerY() - (btnLabelPaint.descent() + btnLabelPaint.ascent()) / 2f
            canvas.drawText(label, rect.centerX(), textY, btnLabelPaint)
        }

        private fun drawActionButton(canvas: Canvas, rect: RectF, label: String, pressed: Boolean) {
            canvas.drawRoundRect(rect, 10f, 10f, if (pressed) btnPressedPaint else btnFillPaint)
            canvas.drawRoundRect(rect, 10f, 10f, btnOutlinePaint)
            btnLabelPaint.textSize = (rect.height() * 0.45f).coerceIn(22f, 36f)
            val textY = rect.centerY() - (btnLabelPaint.descent() + btnLabelPaint.ascent()) / 2f
            canvas.drawText(label, rect.centerX(), textY, btnLabelPaint)
        }

        private fun drawDpadButton(canvas: Canvas, rect: RectF, symbol: String, pressed: Boolean) {
            canvas.drawRoundRect(rect, 12f, 12f, if (pressed) btnPressedPaint else btnFillPaint)
            canvas.drawRoundRect(rect, 12f, 12f, btnOutlinePaint)
            btnLabelPaint.textSize = (rect.height() * 0.48f).coerceIn(26f, 44f)
            val textY = rect.centerY() - (btnLabelPaint.descent() + btnLabelPaint.ascent()) / 2f
            canvas.drawText(symbol, rect.centerX(), textY, btnLabelPaint)
        }
    }
}
