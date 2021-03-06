/*
*  Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
*  WSO2 Inc. licenses this file to you under the Apache License,
*  Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License.
*  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
*  Unless required by applicable law or agreed to in writing,
*  software distributed under the License is distributed on an
*  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
*  KIND, either express or implied.  See the License for the
*  specific language governing permissions and limitations
*  under the License.
*/
package org.ballerinalang.test.utils.debug;

import org.ballerinalang.BLangProgramRunner;
import org.ballerinalang.bre.Context;
import org.ballerinalang.bre.bvm.BLangVM;
import org.ballerinalang.bre.bvm.ControlStack;
import org.ballerinalang.bre.bvm.StackFrame;
import org.ballerinalang.launcher.util.CompileResult;
import org.ballerinalang.model.values.BRefType;
import org.ballerinalang.model.values.BStringArray;
import org.ballerinalang.util.codegen.FunctionInfo;
import org.ballerinalang.util.codegen.PackageInfo;
import org.ballerinalang.util.codegen.ProgramFile;
import org.ballerinalang.util.codegen.WorkerInfo;
import org.ballerinalang.util.debugger.DebugContext;
import org.ballerinalang.util.debugger.VMDebugManager;
import org.ballerinalang.util.debugger.dto.BreakPointDTO;
import org.ballerinalang.util.exceptions.BallerinaException;
import org.ballerinalang.util.program.BLangFunctions;

import java.util.List;

/**
 * {@link DebuggerExecutor} represents executor class which runs the main program when debugging.
 *
 * @since 0.88
 */
public class DebuggerExecutor implements Runnable {
    private CompileResult result;
    private String[] args;
    private TestDebugClientHandler clientHandler;
    private TestDebugServer debugServer;
    private List<BreakPointDTO> breakPoints;
    private PackageInfo mainPkgInfo;

    public DebuggerExecutor(CompileResult result, String[] args, TestDebugClientHandler clientHandler,
                            TestDebugServer debugServer, List<BreakPointDTO> breakPoints) {
        this.result = result;
        this.args = args;
        this.clientHandler = clientHandler;
        this.debugServer = debugServer;
        this.breakPoints = breakPoints;
        init();
    }

    private void init() {
        ProgramFile programFile = result.getProgFile();

        if (!programFile.isMainEPAvailable()) {
            throw new BallerinaException("main function not found in  '" + programFile.getProgramFilePath() + "'");
        }

        // Get the main entry package
        mainPkgInfo = programFile.getEntryPackage();
        if (mainPkgInfo == null) {
            throw new BallerinaException("main function not found in  '" + programFile.getProgramFilePath() + "'");
        }
    }

    @Override
    public void run() {
        ProgramFile programFile = result.getProgFile();

        // Non blocking is not supported in the main program flow..
        Context bContext = new Context(programFile);

        VMDebugManager debugManager = programFile.getDebugManager();
        if (debugManager.isDebugEnabled()) {
            DebugContext debugContext = new DebugContext();
            bContext.setDebugContext(debugContext);
            debugManager.init(programFile, clientHandler, debugServer);
            debugManager.addDebugPoints(breakPoints);
            debugManager.addDebugContext(debugContext);
            debugServer.releaseLock();
            debugManager.waitTillDebuggeeResponds();
        }

        // Invoke package init function
        FunctionInfo mainFuncInfo = BLangProgramRunner.getMainFunction(mainPkgInfo);
        BLangFunctions.invokePackageInitFunction(programFile, mainPkgInfo.getInitFunctionInfo(), bContext);

        // Prepare main function arguments
        BStringArray arrayArgs = new BStringArray();
        for (int i = 0; i < args.length; i++) {
            arrayArgs.add(i, args[i]);
        }

        WorkerInfo defaultWorkerInfo = mainFuncInfo.getDefaultWorkerInfo();

        // Execute workers
        StackFrame callerSF = new StackFrame(mainPkgInfo, -1, new int[0]);
        callerSF.setRefRegs(new BRefType[1]);
        callerSF.getRefRegs()[0] = arrayArgs;

        StackFrame stackFrame = new StackFrame(mainFuncInfo, defaultWorkerInfo, -1, new int[0]);
        stackFrame.getRefRegs()[0] = arrayArgs;
        ControlStack controlStack = bContext.getControlStack();
        controlStack.pushFrame(stackFrame);
        bContext.startTrackWorker();
        bContext.setStartIP(defaultWorkerInfo.getCodeAttributeInfo().getCodeAddrs());

        BLangVM bLangVM = new BLangVM(programFile);
        bLangVM.run(bContext);
        bContext.await();
        debugManager.notifyExit();
    }
}
