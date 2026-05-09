package com.sty.visit.model;

import lombok.Data;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 健硕版：文件保存请求契约
 * 引入 JSR-303 声明式校验，确保业务层不受脏数据污染
 */
@Data
public class FileSaveRequest {
    
    @NotBlank(message = "操作路径不能为空")
    @Size(max = 512, message = "路径超长，存在溢出风险")
    private String path;

    @NotBlank(message = "内容不能为空")
    private String content;
}
