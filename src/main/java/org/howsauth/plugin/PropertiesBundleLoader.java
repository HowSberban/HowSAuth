package org.howsauth.plugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * 语言文件 properties 解析：在标准 properties 基础上支持三引号多行值。
 * <p>
 * 语法：
 * - 单行值：key=value（支持反斜杠续行和 \n \t 等转义）
 * - 多行值：key=""" 开始，换行书写内容，以单独的 """ 行结束
 *   多行值内可包含 # = : 等特殊字符，无需转义；物理换行保留为换行符
 *   仍支持 \n \t 等转义序列（会被解析为对应字符）
 * <p>
 * 解析器只负责文本到 Properties 的转换；文件发现、locale 匹配与告警落点留在 {@link I18n}。
 */
final class PropertiesBundleLoader {

    private PropertiesBundleLoader() {
    }

    static void load(Properties props, BufferedReader reader, Consumer<String> warning) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            String trimmed = line.stripLeading();
            // 空行或注释跳过
            if (trimmed.isEmpty() || trimmed.charAt(0) == '#' || trimmed.charAt(0) == '!') continue;

            // 处理续行（行尾奇数个反斜杠）
            StringBuilder logical = new StringBuilder(trimmed);
            while (countTrailingBackslashes(logical.toString()) % 2 == 1) {
                logical.setLength(logical.length() - 1);
                String next = reader.readLine();
                if (next == null) break;
                logical.append(next.stripLeading());
            }
            String full = logical.toString();

            // 分割 key 和 value
            int eq = full.indexOf('=');
            int colon = full.indexOf(':');
            int sep = (eq < 0) ? colon : (colon < 0 ? eq : Math.min(eq, colon));
            if (sep <= 0) continue;

            String key = full.substring(0, sep).trim();
            String value = full.substring(sep + 1).trim();

            // 三引号多行值
            if (value.startsWith("\"\"\"")) {
                String rest = value.substring(3);
                // 同行结束："""..."""
                if (rest.endsWith("\"\"\"")) {
                    value = rest.substring(0, rest.length() - 3);
                } else {
                    // 多行模式：读取直到 """
                    String[] result = readMultilineValue(reader, rest);
                    value = result[0];
                    if ("false".equals(result[1])) {
                        warning.accept("Unclosed triple-quote in language file for key \"" + key + "\"");
                    }
                }
            }

            props.setProperty(key, unescape(value));
        }
    }

    /**
     * 读取三引号多行值，直到遇到单独的 """ 行或行尾 """。
     * @return [0] = 拼接后的值；[1] = "true" 表示正常闭合，"false" 表示未闭合（读到 EOF）
     */
    private static String[] readMultilineValue(BufferedReader reader, String firstLine) throws IOException {
        List<String> lines = new ArrayList<>();
        if (!firstLine.isEmpty()) {
            lines.add(firstLine);
        }
        boolean closed = false;
        String nextLine;
        while ((nextLine = reader.readLine()) != null) {
            // 整行是 """，结束
            if (nextLine.equals("\"\"\"")) {
                closed = true;
                break;
            }
            // 行尾是 """，结束（取前面的内容）
            if (nextLine.endsWith("\"\"\"")) {
                lines.add(nextLine.substring(0, nextLine.length() - 3));
                closed = true;
                break;
            }
            lines.add(nextLine);
        }
        return new String[]{String.join("\n", lines), Boolean.toString(closed)};
    }

    /** 统计字符串末尾连续反斜杠的数量 */
    private static int countTrailingBackslashes(String s) {
        int count = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            if (s.charAt(i) == '\\') count++;
            else break;
        }
        return count;
    }

    /** properties 标准反转义：换行、制表、回车、反斜杠、Unicode 转义序列 */
    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '\\' -> sb.append('\\');
                    case 'u' -> {
                        if (i + 4 < s.length()) {
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException e) {
                                sb.append('\\').append(next);
                            }
                        } else {
                            sb.append('\\').append(next);
                        }
                    }
                    default -> sb.append('\\').append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
