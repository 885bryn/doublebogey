package com.doublebogey.golftracer.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class LaunchZoneOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    var launchZone: LaunchZone = LaunchZone.Default
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var detectionCandidates: List<LumaMotionCandidate> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var trackPoints: List<ShotTrackPoint> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var onLaunchZoneChanged: ((LaunchZone) -> Unit)? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(64, 0, 255, 0)
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val candidatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        style = Paint.Style.FILL
    }

    private val candidateStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val trackLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val observedTrackPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
    }

    private val predictedTrackPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.MAGENTA
        style = Paint.Style.FILL
    }

    private val zoneRect = RectF()
    private var lastX = 0f
    private var lastY = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        zoneRect.set(
            (launchZone.left * width).toFloat(),
            (launchZone.top * height).toFloat(),
            ((launchZone.left + launchZone.width) * width).toFloat(),
            ((launchZone.top + launchZone.height) * height).toFloat(),
        )

        canvas.drawRect(zoneRect, fillPaint)
        canvas.drawRect(zoneRect, strokePaint)

        drawTrack(canvas)

        for (candidate in detectionCandidates) {
            val cx = (candidate.x * width).toFloat()
            val cy = (candidate.y * height).toFloat()
            canvas.drawCircle(cx, cy, CANDIDATE_RADIUS_PX, candidatePaint)
            canvas.drawCircle(cx, cy, CANDIDATE_RADIUS_PX, candidateStrokePaint)
        }
    }

    private fun drawTrack(canvas: Canvas) {
        for (index in 1 until trackPoints.size) {
            val previous = trackPoints[index - 1]
            val current = trackPoints[index]
            canvas.drawLine(
                (previous.x * width).toFloat(),
                (previous.y * height).toFloat(),
                (current.x * width).toFloat(),
                (current.y * height).toFloat(),
                trackLinePaint,
            )
        }

        for (point in trackPoints) {
            val paint = if (point.predicted) predictedTrackPointPaint else observedTrackPointPaint
            canvas.drawCircle(
                (point.x * width).toFloat(),
                (point.y * height).toFloat(),
                TRACK_POINT_RADIUS_PX,
                paint,
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val updatedLaunchZone = launchZone.dragByPixels(
                    deltaX = (event.x - lastX).toDouble(),
                    deltaY = (event.y - lastY).toDouble(),
                    viewWidth = width,
                    viewHeight = height,
                )
                lastX = event.x
                lastY = event.y

                if (updatedLaunchZone != launchZone) {
                    launchZone = updatedLaunchZone
                    onLaunchZoneChanged?.invoke(updatedLaunchZone)
                }

                true
            }

            else -> super.onTouchEvent(event)
        }
    }

    private companion object {
        const val CANDIDATE_RADIUS_PX = 8f
        const val TRACK_POINT_RADIUS_PX = 6f
    }
}
