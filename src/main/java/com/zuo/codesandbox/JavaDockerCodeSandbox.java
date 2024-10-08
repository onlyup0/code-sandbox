package com.zuo.codesandbox;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.zuo.codesandbox.model.JudgeInfo;
import com.zuo.codesandbox.model.ExecuteCodeRequest;
import com.zuo.codesandbox.model.ExecuteCodeResponse;
import com.zuo.codesandbox.model.ExecuteMessage;
import com.zuo.codesandbox.utils.ProcessUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class JavaDockerCodeSandbox implements CodeSandbox {

    private static final String GLOBAL_CODE_DIR_NAME = "tmpCode";

    private static final String GLOBAL_JAVA_CLASS_NAME = "Main.java";

    private static final long TIME_OUT = 50000L;

    private static final String SECURITY_MANAGER_PATH = "/home/zuo/code-sandbox/src/main/java/com/zuo/codesandbox/security/MySecurityManager.java";

    private static final String SECURITY_MANAGER_CLASS_NAME = "MySecurityManager";

    private static  Boolean FIRST_INIT = false;
    private final boolean[] time_out = {false};



//    public static void main(String[] args) {
//        JavaDockerCodeSandbox javaDockerCodeSandbox = new JavaDockerCodeSandbox();
//        ExecuteCodeRequest executeCodeRequest = new ExecuteCodeRequest();
//        executeCodeRequest.setInputList(Arrays.asList("1 2", "1 3","5 6"));
//        String code = ResourceUtil.readStr("testCode/simpleComputeArgs/Main.java", StandardCharsets.UTF_8);
////        String code = ResourceUtil.readStr("testCode/unsafeCode/RunFileError.java", StandardCharsets.UTF_8);
////        String code = ResourceUtil.readStr("testCode/simpleCompute/Main.java", StandardCharsets.UTF_8);
//        executeCodeRequest.setCode(code);
//        executeCodeRequest.setLanguage("java");
//        ExecuteCodeResponse executeCodeResponse = javaDockerCodeSandbox.executeCode(executeCodeRequest);
//        System.err.println(executeCodeResponse);
//    }

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
//        System.setSecurityManager(new DenySecurityManager());

        List<String> inputList = executeCodeRequest.getInputList();
        String code = executeCodeRequest.getCode();
        String language = executeCodeRequest.getLanguage();


//        1. 把用户的代码保存为文件
        String userDir = System.getProperty("user.dir");
        String globalCodePathName = userDir + File.separator + GLOBAL_CODE_DIR_NAME;
        // 判断全局代码目录是否存在，没有则新建
        if (!FileUtil.exist(globalCodePathName)) {
            FileUtil.mkdir(globalCodePathName);
        }

        // 把用户的代码隔离存放
        String userCodeParentPath = globalCodePathName + File.separator + UUID.randomUUID();
        String userCodePath = userCodeParentPath + File.separator + GLOBAL_JAVA_CLASS_NAME;
        File userCodeFile = FileUtil.writeString(code, userCodePath, StandardCharsets.UTF_8);

//        2. 编译代码，得到 class 文件
        String compileCmd = String.format("javac -encoding utf-8 %s", userCodeFile.getAbsolutePath());
        try {
            Process compileProcess = Runtime.getRuntime().exec(compileCmd);
            ExecuteMessage executeMessage = ProcessUtils.runProcessAndGetMessage(compileProcess, "编译");
            System.err.println("编译结果："+executeMessage);
        } catch (Exception e) {
            ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
            executeCodeResponse.setOutputList(new ArrayList<>());
            executeCodeResponse.setMessage(e.getMessage());
            // 表示代码沙箱错误
            executeCodeResponse.setStatus(2);
            executeCodeResponse.setJudgeInfo(new JudgeInfo());
            return executeCodeResponse;
        }

        // 3. 创建容器，把文件复制到容器内
        // 获取默认的 Docker Client
        DockerClient dockerClient = DockerClientBuilder.getInstance().build();

        // 拉取镜像
        String image = "openjdk:8-alpine";
        if (FIRST_INIT) {
            PullImageCmd pullImageCmd = dockerClient.pullImageCmd(image);
            PullImageResultCallback pullImageResultCallback = new PullImageResultCallback() {
                @Override
                public void onNext(PullResponseItem item) {
//                    System.out.println("下载镜像：" + item.getStatus());
                    super.onNext(item);
                }
            };
            try {
                pullImageCmd
                        .exec(pullImageResultCallback)
                        .awaitCompletion();
            } catch (InterruptedException e) {
                System.err.println("拉取镜像异常");
                throw new RuntimeException(e);
            }
        }

        System.err.println("下载完成");

        // 创建容器

        CreateContainerCmd containerCmd = dockerClient.createContainerCmd(image);
        HostConfig hostConfig = new HostConfig();
        hostConfig.withMemory(100 * 1000 * 1000L);
        hostConfig.withCpuCount(1L);
        hostConfig.withBinds(new Bind(userCodeParentPath, new Volume("/app")));
        CreateContainerResponse createContainerResponse = containerCmd
                .withHostConfig(hostConfig)
                .withNetworkDisabled(true)
                .withAttachStdin(true)
                .withAttachStderr(true)
                .withAttachStdout(true)
                .withTty(true)
                .exec();
        System.err.println("创建容器："+createContainerResponse);
        String containerId = createContainerResponse.getId();

        // 启动容器
        dockerClient.startContainerCmd(containerId).exec();

        // docker exec keen_blackwell java -cp /app Main 1 3
        // 执行命令并获取结果
        List<ExecuteMessage> executeMessageList = new ArrayList<>();
        for (String inputArgs : inputList) {
            StopWatch stopWatch = new StopWatch();

             ExecuteMessage executeMessage=new ExecuteMessage();
            final String[] message = {null};
            final String[] errorMessage = {null};
            final Long[] memory={0l};
            String[] inputArgsArray = inputArgs.split(" ");
            String[] cmdArray = ArrayUtil.append(new String[]{"java", "-cp", "/app", "Main"}, inputArgsArray);
            ExecCreateCmdResponse execCreateCmdResponse = dockerClient.execCreateCmd(containerId)
                    .withCmd(cmdArray)
                    .withAttachStderr(true)
                    .withAttachStdin(true)
                    .withAttachStdout(true)
                    .exec();
            System.err.println("创建执行命令：" + execCreateCmdResponse);
            String execId = execCreateCmdResponse.getId();
            ExecStartResultCallback execStartResultCallback=new ExecStartResultCallback(){
//                @Override
                public void onComplete() {
                    time_out[0] =true;
                    super.onComplete();
                }

                @Override
                public void onNext(Frame frame) {
                    StreamType streamType = frame.getStreamType();
                    if(StreamType.STDERR.equals(streamType)){
                        if(!new String(frame.getPayload()).trim().isEmpty()) {
                            errorMessage[0] = new String(frame.getPayload());
                            System.err.println("输出错误结果" + new String(frame.getPayload()));
                        }
                    }else{
                        if(!new String(frame.getPayload()).trim().isEmpty()){
                            message[0]=new String(frame.getPayload());
                            System.err.println("输出结果"+new String(frame.getPayload()));
                        }
                    }
                    super.onNext(frame);
                }
            };
            StatsCmd statsCmd=dockerClient.statsCmd(containerId);
            ResultCallback<Statistics> exec = statsCmd.exec(new ResultCallback<Statistics>() {

                @Override
                public void close() throws IOException {
                }

                @Override
                public void onStart(Closeable closeable) {
                }

                @Override
                public void onNext(Statistics statistics) {
                    System.err.println("内存占用" + statistics.getMemoryStats().getUsage());
                    memory[0]=Math.max(statistics.getMemoryStats().getUsage(), memory[0]);
                }

                @Override
                public void onError(Throwable throwable) {

                }

                @Override
                public void onComplete() {

                }

            });
            //启动内存监听
            statsCmd.exec(exec);
            try {
//                Thread.sleep(2000);
                stopWatch.start();
                //开始执行语句
                dockerClient.execStartCmd(execId).exec(execStartResultCallback).awaitCompletion(5,TimeUnit.SECONDS);
                stopWatch.stop();
                statsCmd.close();
                Long time=stopWatch.getLastTaskTimeMillis();
                executeMessage.setTime(time);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            executeMessage.setErrorMessage(errorMessage[0]);
            executeMessage.setMessage(message[0]);
            executeMessage.setMemory(memory[0]);
            executeMessageList.add(executeMessage);
        }


        // 4、封装结果，跟原生实现方式完全一致
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        List<String> outputList = new ArrayList<>();
        // 取用时最大值，便于判断是否超时
        Long maxTime=0l;
        Long maxMemory=0l;
        for (ExecuteMessage executeMessage : executeMessageList) {
            System.err.println("结果"+executeMessage.toString());
            String errorMessage = executeMessage.getErrorMessage();
            if (StrUtil.isNotBlank(errorMessage)) {
                executeCodeResponse.setMessage(errorMessage);
                // 用户提交的代码执行中存在错误
                executeCodeResponse.setStatus(3);
                break;
            }
            outputList.add(executeMessage.getMessage());
            Long time = executeMessage.getTime();
            maxTime=Math.max(time,maxTime);
            Long memory=executeMessage.getMemory();
            if(memory==null){
                memory=0l;
            }
            maxMemory=Math.max(memory,maxMemory);
        }
        // 正常运行完成
        if (outputList.size() == executeCodeRequest.getInputList().size()) {
            executeCodeResponse.setStatus(1);
        }
        executeCodeResponse.setOutputList(outputList);
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setTime(maxTime);
        judgeInfo.setMemoryLimit(maxMemory);
        if(time_out[0]==false){
            judgeInfo.setMessage("运行超时");
        }
        executeCodeResponse.setJudgeInfo(judgeInfo);
        //容器清理
        try {
            // 停止容器
            StopContainerCmd stopContainerCmd = dockerClient.stopContainerCmd(containerId);
            stopContainerCmd.exec();
            System.out.println("容器 " + containerId + " 停止成功");

            // 删除容器
//            RemoveContainerCmd removeContainerCmd = dockerClient.removeContainerCmd(containerId);
//            removeContainerCmd.exec();
//            System.out.println("容器 " + containerId + " 删除成功");
        } catch (Exception e) {
            System.err.println("停止或删除容器 " + containerId + " 时发生错误: " + e.getMessage());
        }
//        5. 文件清理
        if (userCodeFile.getParentFile() != null) {
            boolean del = FileUtil.del(userCodeParentPath);
            System.out.println("删除" + (del ? "成功" : "失败"));
        }
        System.out.println(executeCodeResponse);
        return executeCodeResponse;
}

}



