package com.quiz;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/** 텍스트 PDF를 페이지 순서대로 읽는다. OCR은 수행하지 않는다. */
public class PdfReader {
    public static class InputLimitException extends IOException {
        public InputLimitException(String message) {
            super(message);
        }
    }
    public List<String> read() throws IOException {
        return read(Path.of("sample.pdf"));
    }

    /** 보기 전용: 생성용 글자 수/언어 비율 검증을 수행하지 않는다. 추출 설정은 동일하다. */
    public String readPageForDisplay(Path path, int page) throws IOException {
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) throw new IOException("읽을 수 있는 PDF 파일이 아닙니다.");
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            if (page < 1 || page > document.getNumberOfPages()) throw new InputLimitException("PDF 페이지 범위를 벗어났습니다.");
            return extractPageText(document, page, page).getFirst();
        }
    }

    /** 반환 목록의 인덱스 0은 PDF의 1페이지다. 빈 페이지도 유지한다. */
    public List<String> read(Path path) throws IOException {
        return readPages(path.toString());
    }

    /** 기존 전체 읽기 제한과 별개로 PDF의 실제 페이지 수를 확인한다. */
    public int pageCount(Path path) throws IOException {
        if (!Files.isRegularFile(path) || !Files.isReadable(path))
            throw new IOException("읽을 수 있는 PDF 파일이 아닙니다: " + path);
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            return document.getNumberOfPages();
        }
    }

    /** 최대 50페이지의 연속 범위를 읽는다. 첫 결과는 startPage의 원본 페이지다. */
    public List<String> readRange(Path path, int startPage, int endPage) throws IOException {
        if (!Files.isRegularFile(path) || !Files.isReadable(path))
            throw new IOException("읽을 수 있는 PDF 파일이 아닙니다: " + path);
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            int count = document.getNumberOfPages();
            if (startPage < 1 || endPage < startPage || endPage > count)
                throw new InputLimitException("페이지 범위는 1~" + count + " 사이여야 합니다: "
                        + startPage + "~" + endPage);
            if (endPage - startPage + 1 > 50)
                throw new InputLimitException("한 번에 최대 50페이지만 처리할 수 있습니다.");
            List<String> pages = extractPageText(document, startPage, endPage);
            validateText(pages);
            return pages;
        }
    }

    public List<String> readPages(String path) throws IOException {
        Path pdf = Path.of(path);
        if (!Files.exists(pdf)) {
            throw new IOException("PDF 파일이 없습니다: " + pdf.toAbsolutePath());
        }
        if (!Files.isRegularFile(pdf) || !Files.isReadable(pdf)) {
            throw new IOException("읽을 수 있는 PDF 파일이 아닙니다: " + pdf.toAbsolutePath());
        }
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            if (document.getNumberOfPages() > 50) {
                throw new InputLimitException("최대 50페이지까지 지원합니다. 현재 "
                        + document.getNumberOfPages() + "페이지입니다. 다른 PDF를 선택하세요.");
            }
            List<String> pages = extractPageText(document, 1, document.getNumberOfPages());
            validateText(pages);
            return pages;
        } catch (InputLimitException e) {
            throw e;
        } catch (IOException e) {
            throw new IOException("PDF를 읽을 수 없습니다. 유효한 PDF인지, 암호화 또는 손상 여부를 확인하세요: "
                    + pdf.toAbsolutePath(), e);
        }
    }

    private static List<String> extractPageText(PDDocument document, int startPage, int endPage)
            throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        stripper.setSpacingTolerance(0.3f);
        stripper.setAverageCharTolerance(0.3f);
        List<String> pages = new ArrayList<>();
        for (int page = startPage; page <= endPage; page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            pages.add(stripper.getText(document));
        }
        return pages;
    }

    static void validateText(List<String> pages) throws InputLimitException {
        long total = 0;
        long korean = 0;
        for (String page : pages) {
            int[] characters = page.codePoints().toArray();
            for (int character : characters) {
                if (Character.isWhitespace(character) || Character.isSpaceChar(character)) continue;
                total++;
                if (Character.UnicodeScript.of(character) == Character.UnicodeScript.HANGUL) korean++;
            }
        }
        if (total < 100) {
            throw new InputLimitException("추출된 글자가 부족합니다(공백 제외 " + total
                    + "자, 최소 100자). 텍스트 PDF를 선택하세요. 스캔 PDF는 OCR이 필요하며 현재 지원하지 않습니다.");
        }
        if (korean * 10 < total * 3) {
            throw new InputLimitException("한글 비율이 30% 미만입니다. 공백을 제외한 한글 텍스트가 충분한 한국어 강의 PDF를 선택하세요.");
        }
    }
}
