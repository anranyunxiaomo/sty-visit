package com.sty.visit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class StyVisitApplication {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StyVisitApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(StyVisitApplication.class, args);
        log.info("天穹 启动成功！访问地址: http://[服务器IP]:8080");
    }
}
