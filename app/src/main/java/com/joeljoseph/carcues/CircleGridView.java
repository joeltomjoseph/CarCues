package com.joeljoseph.carcues;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;

import java.util.Random;

public class CircleGridView extends View {
    private int numColumns = 5; // Number of columns in the grid
    private int numRows = 5;    // Number of rows in the grid
    private int circleRadius = 20; // Radius of each circle
    private int offsetX = 0;
    private int offsetY = 0;
    private Paint circlePaint;

    public CircleGridView(Context context) {
        super(context);
        init();
    }

    public CircleGridView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CircleGridView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setColor(Color.BLACK);
        circlePaint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();

        int circleSpacingX = width / (numColumns + 1);
        int circleSpacingY = height / (numRows + 1);

        for (int i = 0; i < numRows; i++) {
            for (int j = 0; j < numColumns; j++) {
                int centerX = circleSpacingX * (j + 1) + offsetX;
                int centerY = circleSpacingY * (i + 1) + offsetY;
                // Get circle color for this position
                int circleColor = getCircleColorForPosition(centerX, centerY);
                circlePaint.setColor(circleColor);

                canvas.drawCircle(centerX, centerY, circleRadius, circlePaint);
            }
        }
    }

    private int getCircleColorForPosition(int centerX, int centerY) {
        // todo
        return Color.WHITE;
    }

    public void setOffsetX(int offsetX) {
        this.offsetX = offsetX;
        invalidate(); // Redraw the view
    }

    public void setOffsetY(int offsetY) {
        this.offsetY = offsetY;
        invalidate(); // Redraw the view
    }

    public void setCircleRadius(int circleRadius) {
        this.circleRadius = circleRadius;
        invalidate(); // Redraw the view
    }

    public void setNumColumns(int numColumns) {
        this.numColumns = numColumns;
        invalidate(); // Redraw the view
    }

    public void setNumRows(int numRows) {
        this.numRows = numRows;
        invalidate(); // Redraw the view
    }
}
