package com.dynamo.cr.guieditor.render;

import java.awt.Font;
import java.io.ByteArrayInputStream;

import com.sun.opengl.util.j2d.TextRenderer;

public class GuiFontResource {

    private byte[] fontData;
    private int size;
    private TextRenderer textRenderer;

    public GuiFontResource(byte[] fontData, int size) {
        this.fontData = fontData;
        this.size = size;
    }

    private void createDeferred() {
        if (fontData != null) {
            try {
                Font font = Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(fontData));
                font = font.deriveFont(Font.PLAIN, size);
                textRenderer = new TextRenderer(font, true, true);
            } catch (Throwable e) {
                e.printStackTrace();
            } finally {
                fontData = null;
            }
        }
    }

    public TextRenderer getTextRenderer() {
        if (fontData != null) {
            createDeferred();
        }
        return textRenderer;
    }
}
