package com.quiz.gui;

import com.quiz.SavedQuestion;
import java.util.List;
import java.util.UUID;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.*;

public class QuestionEditorPanelTest {
    @Test public void swingFormEditsAllFieldsAndPreservesSourceForBothTypes() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (SavedQuestion.Type type : SavedQuestion.Type.values()) {
                SavedQuestion original = new SavedQuestion(UUID.randomUUID().toString(), type,
                        "원본 질문", type == SavedQuestion.Type.OX ? List.of("O", "X")
                                : List.of("가", "나", "다", "라"), 0, "원본 해설", "source.pdf", 7, 12);
                QuestionEditorPanel panel = new QuestionEditorPanel(original);
                panel.question.setText("수정 질문");
                panel.explanation.setText("수정 해설");
                panel.answer.setSelectedIndex(type == SavedQuestion.Type.OX ? 1 : 3);
                for (int i = 0; i < panel.options.size(); i++) panel.options.get(i).setText("선택 " + i);
                SavedQuestion edited = panel.editedQuestion();
                assertEquals("수정 질문", edited.question());
                assertEquals("수정 해설", edited.explanation());
                assertEquals(type == SavedQuestion.Type.OX ? 1 : 3, edited.correctIndex());
                assertEquals(type == SavedQuestion.Type.OX ? List.of("O", "X")
                        : List.of("선택 0", "선택 1", "선택 2", "선택 3"), edited.options());
                assertEquals(original.id(), edited.id());
                assertEquals(original.sourcePdf(), edited.sourcePdf());
                assertEquals(original.page(), edited.page());
                assertEquals(original.lineNumber(), edited.lineNumber());
                panel.question.setText(" ");
                try {
                    panel.editedQuestion();
                    fail("빈 질문 거부");
                } catch (IllegalArgumentException expected) {
                    assertFalse(expected.getMessage().isBlank());
                }
            }
        });
    }
}
