package com.quiz.gui;

import javax.swing.*;

interface NotebookDialogs {
    boolean continueWithNext(JFrame parent, int remaining);
    void saveError(JFrame parent, Throwable error);
    static NotebookDialogs swing() {
        return new NotebookDialogs() {
            public boolean continueWithNext(JFrame parent, int remaining) {
                Object[] choices = {"다음 세트 계속", "여기서 종료"};
                return JOptionPane.showOptionDialog(parent, "세트 풀이를 저장했습니다. 남은 세트 " + remaining + "개",
                        "오답노트", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, choices, choices[1]) == 0;
            }
            public void saveError(JFrame parent, Throwable error) {
                JOptionPane.showMessageDialog(parent, QuizMakerFrame.errorMessage("학습 결과를 저장할 수 없습니다. 이 세트에서 멈춥니다.", error),
                        "오류", JOptionPane.ERROR_MESSAGE);
            }
        };
    }
}
