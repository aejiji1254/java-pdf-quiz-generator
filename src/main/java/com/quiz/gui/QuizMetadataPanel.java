package com.quiz.gui;

import com.quiz.QuizMetadata;
import java.awt.GridLayout;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;
import javax.swing.*;

final class QuizMetadataPanel extends JPanel {
    final JTextField title = new JTextField(35);
    final JComboBox<String> subject;
    record Values(String title, String subject) { }

    QuizMetadataPanel(String title, String subject, List<String> subjects) {
        super(new GridLayout(0, 1, 4, 4));
        // JTextField가 붙여넣은 줄바꿈을 공백으로 바꾸지 않게 해 검증에서 명시적으로 거부한다.
        this.title.getDocument().putProperty("filterNewlines", Boolean.FALSE);
        this.title.setText(title);
        this.subject = new JComboBox<>(subjects.toArray(String[]::new));
        this.subject.setEditable(true);
        if (this.subject.getEditor().getEditorComponent() instanceof JTextField editor)
            editor.getDocument().putProperty("filterNewlines", Boolean.FALSE);
        this.subject.setSelectedItem(subject);
        add(new JLabel("이름 (1~60자)")); add(this.title);
        add(new JLabel("과목 (선택, 최대 30자)")); add(this.subject);
    }

    Values values() {
        return new Values(QuizMetadata.title(title.getText()),
                QuizMetadata.subject(String.valueOf(subject.getEditor().getItem())));
    }

    Values request(ToIntFunction<QuizMetadataPanel> show, Consumer<IllegalArgumentException> onError) {
        while (show.applyAsInt(this) == JOptionPane.OK_OPTION) {
            try { return values(); }
            catch (IllegalArgumentException e) { onError.accept(e); }
        }
        return null;
    }
}
