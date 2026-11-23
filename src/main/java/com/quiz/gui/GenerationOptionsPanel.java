package com.quiz.gui;

import com.quiz.GenerationOptions;
import java.awt.FlowLayout;
import java.text.ParseException;
import javax.swing.*;

/** PDF와 수동 입력 화면은 별도 컨트롤을 가지되 같은 옵션 모델을 사용한다. */
final class GenerationOptionsPanel extends JPanel {
    final JCheckBox allOx = new JCheckBox("O/X 가능한 전부", true);
    final JSpinner oxCount = new JSpinner(new SpinnerNumberModel(30, 0, GenerationOptions.MAX_COUNT, 1));
    final JSpinner mcCount = new JSpinner(new SpinnerNumberModel(30, 0, GenerationOptions.MAX_COUNT, 1));
    final JComboBox<GenerationOptions.Direction> direction = new JComboBox<>(GenerationOptions.Direction.values());
    GenerationOptionsPanel() {
        super(new FlowLayout(FlowLayout.LEFT));
        direction.setSelectedItem(GenerationOptions.Direction.MIXED);
        oxCount.setEnabled(false);
        allOx.addActionListener(e -> oxCount.setEnabled(isEnabled() && !allOx.isSelected()));
        add(allOx); add(oxCount); add(new JLabel("객관식 개수")); add(mcCount); add(direction);
    }
    GenerationOptions options() {
        try { oxCount.commitEdit(); mcCount.commitEdit(); }
        catch (ParseException e) { throw new IllegalArgumentException("문항 수를 0~500의 정수로 입력하세요.", e); }
        return new GenerationOptions(allOx.isSelected() ? null : ((Number) oxCount.getValue()).intValue(),
                ((Number) mcCount.getValue()).intValue(), (GenerationOptions.Direction) direction.getSelectedItem());
    }
    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (allOx == null) return;
        allOx.setEnabled(enabled); oxCount.setEnabled(enabled && !allOx.isSelected());
        mcCount.setEnabled(enabled); direction.setEnabled(enabled);
    }
}
