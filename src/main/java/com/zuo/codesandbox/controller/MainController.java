package com.zuo.codesandbox.controller;

import cn.hutool.http.server.HttpServerRequest;
import com.zuo.codesandbox.JavaDockerCodeSandbox;
import com.zuo.codesandbox.model.ExecuteCodeRequest;
import com.zuo.codesandbox.model.ExecuteCodeResponse;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

@RestController("/")
public class MainController {
    private static final String AUTH_HEAD="auth";
    private static final String AUTH_SECRET="zzh";
    @Autowired
    private JavaDockerCodeSandbox javaDockerCodeSandbox;

    @GetMapping("/health")
    public String healthCheck() {
        return "ok";
    }

    @PostMapping("/executeCode")
    public ExecuteCodeResponse executeCOde(@RequestBody ExecuteCodeRequest executeCodeRequest, HttpServletRequest httpServletRequest){
        ExecuteCodeResponse executeCodeResponse=new ExecuteCodeResponse();
        if(executeCodeRequest==null){
            throw new RuntimeException("请求参数为空");
        }
        String auth=httpServletRequest.getHeader(AUTH_HEAD);
        if(!auth.equals(DigestUtils.md5Hex(AUTH_SECRET))||auth.isEmpty()){
            executeCodeResponse.setStatus(403);
            return null;
        }
        executeCodeResponse = javaDockerCodeSandbox.executeCode(executeCodeRequest);
        return executeCodeResponse;
    }
}
