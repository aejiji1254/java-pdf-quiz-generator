package com.quiz;

import com.quiz.gui.QuizWorkflow;
import java.io.IOException;
import java.nio.file.Path;

/** GUI와 동일한 생성 흐름의 PDF 단계별 진단을 콘솔에서 확인한다. */
public final class PdfDiagnostics {
    private PdfDiagnostics() { }

    public static void main(String[] args) {
        if (args.length != 1 && args.length != 3) {
            System.err.println("사용법: com.quiz.PdfDiagnostics <PDF 경로> [시작 페이지 종료 페이지]");
            return;
        }
        try {
            QuizWorkflow.Preview preview = args.length == 1
                    ? new QuizWorkflow().generate(Path.of(args[0]))
                    : new QuizWorkflow().generate(Path.of(args[0]),
                            Integer.parseInt(args[1]), Integer.parseInt(args[2]));
            System.out.println(preview.diagnostics());
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("PDF 처리 실패: " + e.getClass().getSimpleName() + " — " + e.getMessage());
        }
    }
}
