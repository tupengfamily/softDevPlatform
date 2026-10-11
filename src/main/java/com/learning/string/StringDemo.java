package com.learning.string;

/**
 * 字符串(String)
 * <p>
 * char 是基本类型(一个 Unicode 字符), String 是引用类型(指向字符串对象)。
 * String 是不可变对象(Immutable),任何修改都会创建新对象。
 * 字符串常量池: 节省内存,JVM对相同字符串只存储一份。
 * <p>
 * 三种创建方式对比:
 * 1. 字面量: 放入常量池
 * 2. new String(): 在堆中创建新对象
 * 3. intern(): 把字符串放入常量池
 */
public class StringDemo {

    public static void main(String[] args) {
        demonstrateCharAndUnicode();
        demonstrateCreate();
        demonstrateImmutability();
        demonstrateCommonMethods();
        demonstrateTextBlocks();
        demonstrateStringBuilder();
        demonstrateStringPool();
    }

    /**
     * char 与 Unicode: 码元 / 码点
     */
    public static void demonstrateCharAndUnicode() {
        System.out.println("===== char 与 Unicode =====");

        char c1 = 'A';
        char c2 = '中';
        char c3 = '\u0041';               // 就是 'A'
        System.out.println(c1 + " -> " + (int) c1);   // A -> 65
        System.out.println(c2 + " -> " + (int) c2);   // 中 -> 20013
        System.out.println("c3 == c1: " + (c3 == c1));

        // 码元与码点: emoji 需要两个 char(代理对)
        String emoji = "😀";
        System.out.println("length: " + emoji.length());                 // 2
        System.out.println("codePoints: " + emoji.codePoints().count()); // 1

        // 转义字符: \" \\ \n \t 以及 反斜杠u + 4位十六进制(如 A)
        String q = "ABC\n\u4e2d\u6587";
        System.out.println("q.length: " + q.length()); // 6

        System.out.println();
    }

    /**
     * 字符串创建方式
     */
    public static void demonstrateCreate() {
        System.out.println("===== 字符串创建 =====");

        // 方式1: 字面量(推荐,放入字符串常量池)
        String s1 = "Hello";

        // 方式2: new 关键字(在堆中创建新对象)
        String s2 = new String("Hello");

        // 方式3: 字符数组
        char[] chars = {'H', 'e', 'l', 'l', 'o'};
        String s3 = new String(chars);

        // 方式4: 字节数组(用于网络传输等)
        byte[] bytes = {72, 101, 108, 108, 111};
        String s4 = new String(bytes);

        System.out.println("s1=" + s1 + ", s2=" + s2 + ", s3=" + s3 + ", s4=" + s4);

        // 字符串拼接: + 自动把其他类型转字符串;编译期常量会折叠
        String result = "Hello" + " " + "World";
        int age = 25;
        System.out.println("拼接结果: " + result + ", age is " + age);

        // 注意: null 参与拼接得到 "null",不报错更隐蔽
        String n = null;
        System.out.println("n + \"x\" = " + n + "x");

        System.out.println();
    }

    /**
     * 字符串不可变性
     */
    public static void demonstrateImmutability() {
        System.out.println("===== 不可变性 =====");

        String original = "Java";
        System.out.println("原始字符串: " + original);
        System.out.println("hashCode: " + System.identityHashCode(original));

        // 看似修改,实际是创建了新对象
        original = original + " 21";
        System.out.println("拼接后: " + original);
        System.out.println("hashCode: " + System.identityHashCode(original));
        // 注意 hashCode 变了,说明是新的对象

        System.out.println();
    }

    /**
     * 常用方法
     */
    public static void demonstrateCommonMethods() {
        System.out.println("===== 常用方法 =====");

        String text = "  Hello, Java World!  ";

        System.out.println("原字符串: '" + text + "'");

        // 长度
        System.out.println("长度: " + text.length());

        // 去空白
        System.out.println("trim: '" + text.trim() + "'");
        System.out.println("strip: '" + text.strip() + "'"); // Java 11+,支持 Unicode 空白

        // 大小写
        System.out.println("大写: " + text.toUpperCase());
        System.out.println("小写: " + text.toLowerCase());

        // 查找
        System.out.println("包含Java: " + text.contains("Java"));
        System.out.println("首次出现Java的下标: " + text.indexOf("Java"));
        System.out.println("最后出现o的下标: " + text.lastIndexOf("o"));
        System.out.println("以Hello开头: " + text.trim().startsWith("Hello"));
        System.out.println("以!结尾: " + text.trim().endsWith("!"));

        // 截取
        System.out.println("子串[2,7]: " + text.substring(2, 7));

        // 替换
        System.out.println("替换Java为Python: " + text.replace("Java", "Python"));
        System.out.println("正则替换: " + text.replaceAll("\\s+", "_"));

        // 分割
        String[] parts = text.trim().split(",\\s*");
        System.out.println("按逗号分割: " + java.util.Arrays.toString(parts));

        // 格式化
        String formatted = String.format("姓名:%s, 年龄:%d, 分数:%.2f", "张三", 25, 95.5);
        System.out.println("格式化: " + formatted);

        // 空判断: isEmpty 只认长度0, isBlank 连全空白也算空(Java 11+)
        System.out.println("isEmpty: " + "".isEmpty());
        System.out.println("isBlank: " + "   ".isBlank());

        // lines() 逐行处理(Java 11+)
        "line1\nline2".lines().forEach(System.out::println);

        // 实例格式化(Java 15+)
        System.out.println("姓名:%s, 分数:%.2f".formatted("张三", 95.5));

        System.out.println();
    }

    /**
     * 文本块 Text Blocks(Java 13 预览, Java 15 转正): 多行字符串直接书写
     */
    public static void demonstrateTextBlocks() {
        System.out.println("===== Text Blocks =====");

        String json = """
                {
                  "name": "张三",
                  "age": 25
                }
                """;
        System.out.println("JSON: " + json);

        // 公共缩进自动剥离;行尾 \ 续行拼成一行; \s 显式保留空格
        String sql = """
                SELECT * FROM users \
                WHERE id > 100
                """;
        System.out.println("SQL: " + sql);

        // 与 formatted 组合生成动态文本
        int id = 1001;
        String tpl = """
                {
                  "id": %d,
                  "name": "%s"
                }
                """.formatted(id, "张三");
        System.out.println("动态 JSON: " + tpl);

        System.out.println();
    }

    /**
     * StringBuilder: 可变字符串,适合频繁拼接
     */
    public static void demonstrateStringBuilder() {
        System.out.println("===== StringBuilder =====");

        // 简单拼接用 String,频繁拼接用 StringBuilder
        StringBuilder sb = new StringBuilder();
        sb.append("Hello")
          .append(" ")
          .append("Java")
          .append(" 21");
        System.out.println("StringBuilder: " + sb);

        sb.insert(5, " Beautiful");
        System.out.println("插入后: " + sb);

        sb.delete(5, 15);
        System.out.println("删除后: " + sb);

        sb.reverse();
        System.out.println("反转后: " + sb);

        // Java 14+ 可以用链式构造
        String result = new StringBuilder()
                .append("Hello")
                .append(", ")
                .append("World")
                .toString();
        System.out.println("链式构造: " + result);

        System.out.println();
    }

    /**
     * 字符串常量池
     */
    public static void demonstrateStringPool() {
        System.out.println("===== 字符串常量池 =====");

        String s1 = "Java";           // 在常量池
        String s2 = "Java";           // 复用常量池中的同一对象
        String s3 = new String("Java"); // 在堆中创建新对象
        String s4 = s3.intern();      // 把字符串放入常量池,返回常量池中的对象

        System.out.println("s1 == s2: " + (s1 == s2));       // true(同一对象)
        System.out.println("s1 == s3: " + (s1 == s3));       // false(不同对象)
        System.out.println("s1 == s4: " + (s1 == s4));       // true(intern后)
        System.out.println("s1.equals(s3): " + s1.equals(s3)); // true(内容相等)

        System.out.println();
    }
}