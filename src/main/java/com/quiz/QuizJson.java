package com.quiz;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 저장 스키마에 필요한 JSON 값만 엄격하게 처리한다. */
final class QuizJson {
    private QuizJson() { }

    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value);
        return out.toString() + "\n";
    }

    private static void append(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            out.append('"');
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                        else out.append(c);
                    }
                }
            }
            out.append('"');
        } else if (value instanceof Boolean bool) {
            out.append(bool);
        } else if (value instanceof Integer number) {
            out.append(number);
        } else if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) out.append(',');
                append(out, list.get(i));
            }
            out.append(']');
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("JSON 키는 문자열이어야 합니다.");
                }
                if (!first) out.append(',');
                first = false;
                append(out, key);
                out.append(':');
                append(out, entry.getValue());
            }
            out.append('}');
        } else {
            throw new IllegalArgumentException("지원하지 않는 JSON 값입니다.");
        }
    }

    static Object parse(String text) {
        return new Parser(text).parse();
    }

    private static final class Parser {
        private final String text;
        private int position;

        Parser(String text) {
            this.text = text;
        }

        Object parse() {
            Object result = value(0);
            whitespace();
            if (position != text.length()) fail("JSON 뒤에 불필요한 내용이 있습니다.");
            return result;
        }

        private Object value(int depth) {
            if (depth > 12) fail("JSON 구조가 너무 깊습니다.");
            whitespace();
            if (position >= text.length()) fail("JSON이 끝났습니다.");
            char c = text.charAt(position);
            if (c == '{') return object(depth + 1);
            if (c == '[') return array(depth + 1);
            if (c == '"') return string();
            if (text.startsWith("true", position)) {
                position += 4;
                return true;
            }
            if (text.startsWith("false", position)) {
                position += 5;
                return false;
            }
            if (text.startsWith("null", position)) {
                position += 4;
                return null;
            }
            if (c == '-' || c >= '0' && c <= '9') return integer();
            fail("예상하지 못한 JSON 값입니다.");
            return null;
        }

        private Map<String, Object> object(int depth) {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            whitespace();
            if (take('}')) return result;
            while (true) {
                whitespace();
                if (position >= text.length() || text.charAt(position) != '"') fail("객체 키가 없습니다.");
                String key = string();
                whitespace();
                expect(':');
                Object item = value(depth);
                if (result.containsKey(key)) fail("중복 JSON 키입니다: " + key);
                result.put(key, item);
                whitespace();
                if (take('}')) return result;
                expect(',');
            }
        }

        private List<Object> array(int depth) {
            expect('[');
            List<Object> result = new ArrayList<>();
            whitespace();
            if (take(']')) return result;
            while (true) {
                result.add(value(depth));
                whitespace();
                if (take(']')) return result;
                expect(',');
            }
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (position < text.length()) {
                char c = text.charAt(position++);
                if (c == '"') {
                    for (int i = 0; i < out.length(); i++) {
                        char character = out.charAt(i);
                        if (Character.isHighSurrogate(character)) {
                            if (++i >= out.length() || !Character.isLowSurrogate(out.charAt(i)))
                                fail("유니코드 짝이 맞지 않습니다.");
                        } else if (Character.isLowSurrogate(character)) {
                            fail("유니코드 짝이 맞지 않습니다.");
                        }
                    }
                    return out.toString();
                }
                if (c != '\\') {
                    if (c < 0x20) fail("문자열에 제어 문자가 있습니다.");
                    out.append(c);
                    continue;
                }
                if (position >= text.length()) fail("문자열 이스케이프가 끝났습니다.");
                char escape = text.charAt(position++);
                switch (escape) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (position + 4 > text.length()) fail("유니코드 이스케이프가 짧습니다.");
                        try {
                            out.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                        } catch (NumberFormatException e) {
                            fail("유니코드 이스케이프가 올바르지 않습니다.");
                        }
                        position += 4;
                    }
                    default -> fail("문자열 이스케이프가 올바르지 않습니다.");
                }
            }
            fail("문자열이 닫히지 않았습니다.");
            return null;
        }

        private Integer integer() {
            int start = position;
            take('-');
            if (take('0')) {
                if (position < text.length() && digit(text.charAt(position)))
                    fail("정수에 불필요한 0이 있습니다.");
            } else {
                if (position >= text.length() || text.charAt(position) < '1'
                        || text.charAt(position) > '9') fail("정수가 올바르지 않습니다.");
                while (position < text.length() && digit(text.charAt(position))) position++;
            }
            try {
                return Integer.valueOf(text.substring(start, position));
            } catch (NumberFormatException e) {
                fail("정수 범위를 벗어났습니다.");
                return null;
            }
        }

        private static boolean digit(char c) {
            return c >= '0' && c <= '9';
        }

        private void whitespace() {
            while (position < text.length() && " \t\r\n".indexOf(text.charAt(position)) >= 0) position++;
        }

        private boolean take(char c) {
            if (position < text.length() && text.charAt(position) == c) {
                position++;
                return true;
            }
            return false;
        }

        private void expect(char c) {
            whitespace();
            if (!take(c)) fail("'" + c + "'가 필요합니다.");
        }

        private void fail(String message) {
            throw new IllegalArgumentException(message + " (위치 " + position + ")");
        }
    }
}
