package com.zuo.zojcodesandbox;

import com.zuo.codesandbox.MainApplication;
import com.zuo.codesandbox.security.MySecurityManager;
import org.assertj.core.internal.Classes;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = MainApplication.class)
class ZojCodeSandboxApplicationTests {

    @Test
    void contextLoads() {
        MySecurityManager mySecurityManager = new MySecurityManager();
        System.out.println(mySecurityManager);
    }

}
