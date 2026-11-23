package com.quiz.gui;

import com.quiz.SavedQuestion;
import com.quiz.PdfPageService;
import com.quiz.QuizMetadata;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/** 검토 문항의 편집 폼. 적용할 때 SavedQuestion의 저장 검증을 그대로 사용한다. */
final class QuestionEditorPanel extends JPanel {
    final JTextArea question = field(4);
    final List<JTextArea> options = new ArrayList<>();
    final JComboBox<String> answer;
    final JTextArea explanation = field(3);
    private final SavedQuestion original;
    private final SavedQuestion.Type type;
    private PdfPageService.Selection selection;

    QuestionEditorPanel(SavedQuestion original) {
        this(original, original.type(), original.sourcePdf());
    }

    QuestionEditorPanel(SavedQuestion.Type type, String sourcePdf) {
        this(null, type, sourcePdf);
    }

    QuestionEditorPanel(SavedQuestion.Type type, String sourcePdf, PdfPageService.Selection selection) {
        this(type, sourcePdf);
        this.selection = selection;
        if (selection != null) { explanation.setText(selection.explanation()); answer.setSelectedIndex(-1); }
    }

    private QuestionEditorPanel(SavedQuestion original, SavedQuestion.Type type, String sourcePdf) {
        super(new BorderLayout());
        this.original = original;
        this.type = type;
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        question.setText(original == null ? "" : original.question());
        addField(form, "질문", question);
        boolean ox = type == SavedQuestion.Type.OX;
        if (!ox) {
            for (int i = 0; i < 4; i++) {
                JTextArea option = field(2);
                option.setText(original == null ? "" : original.options().get(i));
                options.add(option);
                addField(form, "선택지 " + (i + 1), option);
            }
        }
        answer = new JComboBox<>(ox ? new String[] {"O", "X"}
                : new String[] {"1번", "2번", "3번", "4번"});
        answer.setSelectedIndex(original == null ? 0 : original.correctIndex());
        form.add(new JLabel("정답"));
        form.add(answer);
        explanation.setText(original == null ? "" : original.explanation());
        addField(form, "해설", explanation);
        form.add(new JLabel("출처: " + QuizMetadata.portableSource(sourcePdf)
                + (original == null || original.page() == null ? "" : " · " + original.page() + "페이지")
                + (original == null || original.lineNumber() == null ? "" : " · " + original.lineNumber() + "행")));
        if (original == null) form.add(new JLabel("해설은 필수입니다. [직접 작성] 표시는 자동으로 붙습니다."));
        JScrollPane scroll = new JScrollPane(form);
        scroll.setPreferredSize(new Dimension(620, ox ? 400 : 600));
        add(scroll, BorderLayout.CENTER);
    }

    SavedQuestion editedQuestion() {
        return original.edited(question.getText(), original.type() == SavedQuestion.Type.OX
                ? List.of("O", "X") : options.stream().map(JTextArea::getText).toList(),
                answer.getSelectedIndex(), explanation.getText());
    }

    QuizWorkflow.Preview addTo(QuizWorkflow workflow, QuizWorkflow.Preview preview) {
        if (selection != null && answer.getSelectedIndex() < 0) throw new IllegalArgumentException("정답을 선택하세요");
        return workflow.addQuestion(preview, type, question.getText(),
                type == SavedQuestion.Type.OX ? List.of("O", "X")
                        : options.stream().map(JTextArea::getText).toList(),
                answer.getSelectedIndex(), explanation.getText(), selection == null ? null : selection.page(), selection == null ? null : selection.line());
    }

    private static JTextArea field(int rows) {
        JTextArea field = new JTextArea(rows, 40);
        field.setLineWrap(true);
        field.setWrapStyleWord(true);
        return field;
    }

    private static void addField(JPanel form, String label, JTextArea field) {
        form.add(new JLabel(label));
        form.add(new JScrollPane(field));
    }
}
