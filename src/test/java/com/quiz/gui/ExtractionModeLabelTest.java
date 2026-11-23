package com.quiz.gui;

import com.quiz.*;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class ExtractionModeLabelTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @SuppressWarnings("unchecked")
    @Test public void labelsDescribeExperimentalChoiceWithoutChangingModeValues() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            QuizMakerFrame frame = new QuizMakerFrame(NotebookFixtures.workflow(temporary.getRoot().toPath()));
            try {
                var field = QuizMakerFrame.class.getDeclaredField("extractionMode"); field.setAccessible(true);
                JComboBox<TermExtractor.Mode> modes = (JComboBox<TermExtractor.Mode>) field.get(frame);
                var pdf = QuizMakerFrame.class.getDeclaredField("selectedPdf"); pdf.setAccessible(true);
                pdf.set(frame, java.nio.file.Path.of("sample.pdf"));
                assertEquals(TermExtractor.Mode.LEGACY, modes.getSelectedItem());
                JList<TermExtractor.Mode> list = new JList<>();
                JLabel experimental = (JLabel) modes.getRenderer().getListCellRendererComponent(list, TermExtractor.Mode.STRUCTURED, 1, false, false);
                assertEquals("STRUCTURED (실험적 — 원문 대조 필요)", experimental.getText());
                modes.setSelectedItem(TermExtractor.Mode.STRUCTURED);
                assertEquals(TermExtractor.Mode.STRUCTURED, modes.getSelectedItem());
                JLabel legacy = (JLabel) modes.getRenderer().getListCellRendererComponent(list, TermExtractor.Mode.LEGACY, 0, false, false);
                assertEquals("LEGACY", legacy.getText());
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            finally { frame.dispose(); }
        });
    }
}
