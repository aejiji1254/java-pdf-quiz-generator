package com.quiz;

import java.lang.classfile.*;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/** 별도 테스트 JVM 전용. 제품 소스/알고리즘은 바꾸지 않고 기본 생성자의 난수 생성만 계측한다. */
public final class McBaselineAgent {
    static volatile int changedInstructions;
    static volatile Throwable failure;

    public static void premain(String arguments, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                    ProtectionDomain domain, byte[] bytes) {
                if (!"com/quiz/QuizGenerator".equals(name)) return null;
                try {
                    ClassDesc seeded = ClassDesc.of("com.quiz.McBaselineRandom");
                    return ClassFile.of().transformClass(ClassFile.of().parse(bytes), (builder, element) -> {
                        if (element instanceof MethodModel method && method.methodName().equalsString("<init>")
                                && method.methodType().equalsString("()V")) {
                            builder.transformMethod(method, MethodTransform.transformingCode((code, instruction) -> {
                                if (instruction instanceof NewObjectInstruction create && create.className().asInternalName().equals("java/util/Random")) {
                                    code.new_(seeded); changedInstructions++;
                                } else if (instruction instanceof InvokeInstruction invoke && invoke.owner().asInternalName().equals("java/util/Random")
                                        && invoke.name().equalsString("<init>") && invoke.type().equalsString("()V")) {
                                    code.invokespecial(seeded, "<init>", MethodTypeDesc.ofDescriptor("()V")); changedInstructions++;
                                } else code.with(instruction);
                            }));
                        } else builder.with(element);
                    });
                } catch (RuntimeException e) { failure = e; throw e; }
            }
        });
    }
}
