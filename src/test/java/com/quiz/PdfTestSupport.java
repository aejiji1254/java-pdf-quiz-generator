package com.quiz;

import java.io.File;
import java.io.IOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

final class PdfTestSupport {
    private PdfTestSupport() { }

    static PDType0Font koreanFont(PDDocument document) throws IOException {
        String fontPath = System.getProperty("test.koreanFont", "C:/Windows/Fonts/malgun.ttf");
        return PDType0Font.load(document, new File(fontPath));
    }
}
