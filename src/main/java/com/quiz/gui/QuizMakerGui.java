package com.quiz.gui;

import java.awt.GraphicsEnvironment;
import javax.swing.SwingUtilities;

/** Swing 진입점. 기존 콘솔 Main은 그대로 사용한다. */
public final class QuizMakerGui {
    private QuizMakerGui() { }

    public static void main(String[] args) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("화면을 표시할 수 없는 환경입니다.");
            return;
        }
        SwingUtilities.invokeLater(() -> new QuizMakerFrame(new QuizWorkflow()).setVisible(true));
    }
}
