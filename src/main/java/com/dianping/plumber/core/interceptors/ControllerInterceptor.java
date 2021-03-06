package com.dianping.plumber.core.interceptors;

import java.lang.reflect.Field;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.Logger;
import org.springframework.context.ApplicationContext;

import com.dianping.plumber.config.PlumberConfig;
import com.dianping.plumber.core.*;
import com.dianping.plumber.core.definitions.PlumberControllerDefinition;
import com.dianping.plumber.core.definitions.PlumberPipeDefinition;
import com.dianping.plumber.exception.PlumberRuntimeException;
import com.dianping.plumber.utils.*;

/**
 * Created with IntelliJ IDEA.
 * Author: liangjun.zhong
 * Date: 14-11-10
 * Time: PM10:01
 * To change this template use File | Settings | File Templates.
 */
public class ControllerInterceptor implements Interceptor {

    private Logger logger = Logger.getLogger(ControllerInterceptor.class);

    @Override
    public ResultType intercept(InvocationContext invocation) throws Exception {

        String controllerName = invocation.getControllerName();
        PlumberController controller = getController(invocation);

        Map<String, Object> paramsForController = invocation.getParamsForController();
        Map<String, Object> tempParamsForPagelets = new HashMap<String, Object>();
        Map<String, Object> tempModelForControllerView = new HashMap<String, Object>();
        tempModelForControllerView.put(PlumberGlobals.PLUMBER_JS_PLACEHOLDER,
            PlumberGlobals.PLUMBER_JS);

        ResultType resultType = controller.execute(paramsForController, tempParamsForPagelets,
            tempModelForControllerView);
        if (resultType != ResultType.SUCCESS)
            return resultType;

        fillInvocationContext(invocation, paramsForController, tempParamsForPagelets,
            tempModelForControllerView);

        resultType = invocation.invoke();
        if (resultType != ResultType.SUCCESS)
            return resultType;

        PlumberControllerDefinition controllerDefinition = PlumberWorkerDefinitionsRepo
            .getPlumberControllerDefinition(controllerName);
        String renderResult = ViewRenderUtils.getViewRenderResult(controllerDefinition,
            invocation.getModelForControllerView());

        HttpServletResponse response = invocation.getResponse();
        ResponseUtils.flushBuffer(response, renderResult);

        List<PlumberPipeDefinition> pipeDefinitions = controllerDefinition.getPipeDefinitions();
        if (!CollectionUtils.isEmpty(pipeDefinitions)) {

            int pipeNum = pipeDefinitions.size();
            Date startTime = invocation.getStartTime();
            long timeLimit = PlumberConfig.getResponseTimeout();
            LinkedBlockingQueue<String> pipeRenderResultQueue = invocation
                .getPipeRenderResultQueue();
            while (pipeNum > 0 && !TimeUtils.isTimeout(startTime, timeLimit)) {
                String pipeRenderResult = pipeRenderResultQueue.poll(
                    TimeUtils.getRemainingTime(startTime, timeLimit), TimeUnit.MILLISECONDS);
                if (pipeRenderResult == null) { // timeout
                    break;
                }
                if (!PlumberGlobals.EMPTY_RENDER_RESULT.equals(pipeRenderResult)) {
                    ResponseUtils.flushBuffer(response, pipeRenderResult);
                }
                pipeNum = pipeNum - 1;
            }

            // only with pipes need to ensure html to be unclosed, and the close tag will be flushed by framework finally
            ResponseUtils.flushBuffer(response, PlumberGlobals.CHUNKED_END);
        }

        invocation.getResultReturnedFlag().setReturned();

        return ResultType.SUCCESS;
    }

    private PlumberController getController(InvocationContext invocation) {

        String controllerName = invocation.getControllerName();
        ApplicationContext applicationContext = invocation.getApplicationContext();
        PlumberController controller = (PlumberController) applicationContext
            .getBean(controllerName);
        if (controller == null) {
            throw new PlumberRuntimeException("can not find your plumberController : "
                                              + controllerName + " in spring applicationContext");
        }

        PlumberControllerDefinition definition = PlumberWorkerDefinitionsRepo
            .getPlumberControllerDefinition(controllerName);
        List<Field> paramFromRequestFields = definition.getParamFromRequestFields();
        Map<String, Object> paramsForController = invocation.getParamsForController();
        injectAnnotationFields(controllerName, controller, paramFromRequestFields,
            paramsForController);

        resetFields(controller, definition);

        return controller;
    }

    private void injectAnnotationFields(String controllerName, PlumberController controller,
                                        List<Field> paramFromRequestFields,
                                        Map<String, Object> paramsForController) {

        if (!CollectionUtils.isEmpty(paramFromRequestFields) && paramsForController != null) {
            for (Field field : paramFromRequestFields) {
                String fieldName = field.getName();
                Object fieldValue = paramsForController.get(fieldName);
                if (fieldValue != null) {
                    try {
                        field.set(controller, fieldValue);
                    } catch (Exception e) {
                        String msg = "inject annotation field of " + fieldName + " for controller "
                                     + controllerName + " failure";
                        logger.error(msg, new PlumberRuntimeException(msg, e));
                    }
                }
            }
        }

    }

    private void resetFields(PlumberController controller, PlumberControllerDefinition definition) {
        controller.setBarrierNames(definition.getBarrierNames());
        controller.setPipeNames(definition.getPipeNames());
    }

    private void fillInvocationContext(InvocationContext invocation,
                                       Map<String, Object> paramsForController,
                                       Map<String, Object> tempParamsForPagelets,
                                       Map<String, Object> tempModelForControllerView) {

        MapUtils.convert(paramsForController, invocation.getParamsFromRequest());
        MapUtils.convert(tempParamsForPagelets, invocation.getParamsForPagelets());
        MapUtils.convert(tempModelForControllerView, invocation.getModelForControllerView());

    }

}
