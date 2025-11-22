package com.qwerlty.myojbackendquestionservice.validation;

import com.qwerlty.myojbackendcommon.common.ErrorCode;
import com.qwerlty.myojbackendcommon.exception.BusinessException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Rejects only high-confidence source language mismatches before a judge task is created.
 */
@Component
public class SubmissionLanguageValidator {

    private static final Pattern CPP_INCLUDE = Pattern.compile("(?m)^\\s*#\\s*include\\s*[<\"]");
    private static final Pattern CPP_NAMESPACE = Pattern.compile("\\busing\\s+namespace\\s+std\\s*;");
    private static final Pattern CPP_MAIN = Pattern.compile("\\b(?:int|signed)\\s+main\\s*\\(");

    private static final Pattern JAVA_CLASS = Pattern.compile("\\bclass\\s+[A-Za-z_$][\\w$]*\\b");
    private static final Pattern JAVA_MAIN = Pattern.compile("\\bpublic\\s+static\\s+void\\s+main\\s*\\(");

    private static final Pattern GO_PACKAGE_MAIN = Pattern.compile("(?m)^\\s*package\\s+main\\b");
    private static final Pattern GO_MAIN = Pattern.compile("\\bfunc\\s+main\\s*\\(");

    public void validate(String selectedLanguage, String code) {
        if (StringUtils.isBlank(code)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "代码不能为空");
        }

        // Hybrid snippets and language names in comments should be left to the compiler when
        // the selected language itself has a valid, high-confidence signature.
        if (hasSignature(selectedLanguage, code)) {
            return;
        }

        String detectedLanguage = detect(code);
        if (detectedLanguage == null || detectedLanguage.equals(selectedLanguage)) {
            return;
        }

        throw new BusinessException(
                ErrorCode.PARAMS_ERROR,
                "当前选择的是 " + displayName(selectedLanguage)
                        + "，但代码看起来是 " + displayName(detectedLanguage)
                        + "，请切换为 " + displayName(detectedLanguage) + " 后再提交"
        );
    }

    private String detect(String code) {
        if (hasCppSignature(code)) {
            return "cpp";
        }
        if (hasJavaSignature(code)) {
            return "java";
        }
        if (hasGoSignature(code)) {
            return "go";
        }
        return null;
    }

    private boolean hasSignature(String language, String code) {
        if ("cpp".equals(language)) {
            return hasCppSignature(code);
        }
        if ("java".equals(language)) {
            return hasJavaSignature(code);
        }
        if ("go".equals(language)) {
            return hasGoSignature(code);
        }
        return false;
    }

    private boolean hasCppSignature(String code) {
        return CPP_INCLUDE.matcher(code).find()
                || (CPP_NAMESPACE.matcher(code).find() && CPP_MAIN.matcher(code).find());
    }

    private boolean hasJavaSignature(String code) {
        return JAVA_CLASS.matcher(code).find() && JAVA_MAIN.matcher(code).find();
    }

    private boolean hasGoSignature(String code) {
        return GO_PACKAGE_MAIN.matcher(code).find() && GO_MAIN.matcher(code).find();
    }

    private String displayName(String language) {
        if ("cpp".equals(language)) {
            return "C++";
        }
        if ("java".equals(language)) {
            return "Java";
        }
        if ("go".equals(language)) {
            return "Go";
        }
        return language;
    }
}
