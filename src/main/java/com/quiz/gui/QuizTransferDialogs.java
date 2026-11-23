package com.quiz.gui;

import com.quiz.QuizTransfer;
import java.awt.Component;
import java.nio.file.Path;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;

/** 파일 선택·확인은 EDT에서 수행하고 실제 입출력은 프레임의 worker가 담당한다. */
interface QuizTransferDialogs {
    Path exportTarget(Component owner, String defaultName);
    Path importSource(Component owner);
    boolean copyConflict(Component owner, QuizTransfer.ImportPlan plan);
    void info(Component owner, String message);
    void error(Component owner, String message, Throwable error);

    static QuizTransferDialogs swing() {
        return new QuizTransferDialogs() {
            private JFileChooser chooser() {
                JFileChooser chooser = new JFileChooser();
                chooser.setFileFilter(new FileNameExtensionFilter("퀴즈 세트 JSON", "json"));
                chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
                return chooser;
            }
            public Path exportTarget(Component owner, String defaultName) {
                JFileChooser chooser = chooser();
                chooser.setSelectedFile(new java.io.File(defaultName));
                chooser.setAccessory(new JLabel("개인 폴더 경로는 파일명만 남기고 내보냅니다"));
                if (chooser.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) return null;
                Path file = chooser.getSelectedFile().toPath();
                return file.toString().endsWith(".json") ? file : file.resolveSibling(file.getFileName() + ".json");
            }
            public Path importSource(Component owner) {
                JFileChooser chooser = chooser();
                return chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile().toPath() : null;
            }
            public boolean copyConflict(Component owner, QuizTransfer.ImportPlan plan) {
                Object[] choices = {"가져오지 않음", "복사본으로 가져오기"};
                return JOptionPane.showOptionDialog(owner, plan.message(), "가져오기 ID 충돌",
                        JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, choices, choices[0]) == 1;
            }
            public void info(Component owner, String message) { JOptionPane.showMessageDialog(owner, message); }
            public void error(Component owner, String message, Throwable error) {
                JOptionPane.showMessageDialog(owner, QuizMakerFrame.errorMessage(message, error), "전송 오류", JOptionPane.ERROR_MESSAGE);
            }
        };
    }
}
