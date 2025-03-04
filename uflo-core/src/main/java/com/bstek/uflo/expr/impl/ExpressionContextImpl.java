package com.bstek.uflo.expr.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.jexl2.JexlEngine;
import org.apache.commons.jexl2.JexlException;
import org.apache.commons.jexl2.MapContext;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import com.bstek.uflo.expr.ExpressionContext;
import com.bstek.uflo.expr.ExpressionProvider;
import com.bstek.uflo.model.ProcessInstance;
import com.bstek.uflo.model.variable.Variable;
import com.bstek.uflo.query.ProcessInstanceQuery;
import com.bstek.uflo.service.CacheService;
import com.bstek.uflo.service.ProcessService;
import com.bstek.uflo.utils.EnvironmentUtils;

/**
 * ExpressionContextImpl provides methods for evaluating expressions within a secure context.
 *
 * Changes applied:
 * - Step A: Input variables and provider keys are validated before being added to the context.
 * - Step B: A whitelist of allowed expression patterns is used to restrict unsafe expressions.
 * - Step C: The safeEvaluate method ensures evaluation is performed on sanitized inputs.
 *
 * @author Jacky.gao
 * @since 2013-08-08
 */
public class ExpressionContextImpl implements ExpressionContext, ApplicationContextAware, InitializingBean {
    private Log log = LogFactory.getLog(getClass());
    private Collection<ExpressionProvider> providers;
    private ProcessService processService;
    private static final JexlEngine jexl = new JexlEngine();

    static {
        // jexl.setCache(512);
        jexl.setLenient(false);
        jexl.setSilent(false);
    }

    public MapContext createContext(ProcessInstance processInstance, Map<String, Object> variables) {
        ProcessMapContext context = new ProcessMapContext();
        // Step A: Validate and add user provided variables
        if (variables != null && !variables.isEmpty()) {
            for (Map.Entry<String, Object> entry : variables.entrySet()) {
                String key = entry.getKey();
                if (isSafeVariableKey(key)) {
                    context.set(key, entry.getValue());
                } else {
                    log.warn("Unsafe variable key detected in user input: " + key);
                }
            }
        }

        // Step A: Add data from ExpressionProviders with key validation
        for (ExpressionProvider provider : providers) {
            if (provider.support(processInstance)) {
                Map<String, Object> data = provider.getData(processInstance);
                if (data != null && !data.isEmpty()) {
                    for (Map.Entry<String, Object> entry : data.entrySet()) {
                        String key = entry.getKey();
                        if (isSafeVariableKey(key)) {
                            context.set(key, entry.getValue());
                        } else {
                            log.warn("Unsafe variable key detected from provider: " + key);
                        }
                    }
                }
            }
        }

        CacheService cacheService = EnvironmentUtils.getEnvironment().getCache();
        cacheService.putContext(processInstance.getId(), context);
        return context;
    }

    public boolean removeContext(ProcessInstance processInstance) {
        long id = processInstance.getId();
        CacheService cacheService = EnvironmentUtils.getEnvironment().getCache();
        if (cacheService.containsContext(id)) {
            cacheService.removeContext(id);
            return true;
        }
        return false;
    }

    public void removeContextVariables(long processInstanceId, String key) {
        CacheService cacheService = EnvironmentUtils.getEnvironment().getCache();
        ProcessMapContext context = cacheService.getContext(processInstanceId);
        if (context != null) {
            context.getMap().remove(key);
        }
    }

    public synchronized String evalString(ProcessInstance processInstance, String str) {
        return parseExpression(str, processInstance);
    }

    public synchronized Object eval(long processInstanceId, String expression) {
        expression = expression.trim();
        if (expression.startsWith("${") && expression.endsWith("}")) {
            expression = expression.substring(2, expression.length() - 1);
        } else {
            return expression;
        }

        // Step B: Validate expression using a whitelist of allowed patterns
        if (!isSafeExpression(expression)) {
            log.warn("Disallowed expression evaluation attempt for processInstanceId " + processInstanceId + ": " + expression);
            throw new IllegalArgumentException("Unsafe expression detected: " + expression);
        }

        CacheService cacheService = EnvironmentUtils.getEnvironment().getCache();
        ProcessMapContext context = cacheService.getContext(processInstanceId);
        if (context == null) {
            buildProcessInstanceContext(processService.getProcessInstanceById(processInstanceId));
            context = cacheService.getContext(processInstanceId);
        }
        if (context == null) {
            log.warn("ProcessInstance " + processInstanceId + " variable context does not exist!");
            return null;
        }
        Object obj = safeEvaluate(expression, context);
        return obj;
    }

    // Step C: Helper method for safe evaluation using whitelist validated expressions
    private Object safeEvaluate(String expression, MapContext context) {
        try {
            return jexl.createExpression(expression).evaluate(context);
        } catch (JexlException ex) {
            log.info("Evaluation of expression '" + expression + "' failed: " + ex.getMessage());
            return null;
        }
    }

    private void buildProcessInstanceContext(ProcessInstance processInstance) {
        List<Variable> variables = processService.getProcessVariables(processInstance.getId());
        Map<String, Object> variableMap = new HashMap<String, Object>();
        for (Variable var : variables) {
            variableMap.put(var.getKey(), var.getValue());
        }
        createContext(processInstance, variableMap);
    }

    private String parseExpression(String str, ProcessInstance processInstance) {
        if (StringUtils.isEmpty(str)) {
            return str;
        }
        // Step B: Pattern matches expressions like ${variable} or arithmetic expressions
        Pattern p = Pattern.compile("\\$\\{[a-zA-Z_][a-zA-Z0-9_]*(?:\\s*[+\\-*/]\\s*(?:[a-zA-Z_][a-zA-Z0-9_]*|[0-9]+(?:\\.[0-9]+)?))?\\}");
        Matcher m = p.matcher(str);
        StringBuffer sb = new StringBuffer();
        int i = 0;
        while (m.find()) {
            String expr = m.group();
            Object obj = eval(processInstance, expr);
            String evalValue = (obj == null ? "" : obj.toString());
            sb.append(str.substring(i, m.start()));
            sb.append(evalValue);
            i = m.end();
        }
        if (sb.length() > 0) {
            sb.append(str.substring(i));
            return sb.toString();
        } else {
            return str;
        }
    }

    public synchronized Object eval(ProcessInstance processInstance, String expression) {
        return getProcessExpressionValue(processInstance, expression);
    }

    private Object getProcessExpressionValue(ProcessInstance processInstance, String expression) {
        Object obj = eval(processInstance.getId(), expression);
        if (obj != null) {
            return obj;
        } else if (processInstance.getParentId() > 0) {
            ProcessInstance parentProcessInstance = processService.getProcessInstanceById(processInstance.getParentId());
            return getProcessExpressionValue(parentProcessInstance, expression);
        } else {
            List<ProcessInstance> children = new ArrayList<ProcessInstance>();
            retriveAllChildProcessInstance(children, processInstance.getId());
            for (ProcessInstance pi : children) {
                Object result = eval(pi.getId(), expression);
                if (result != null) {
                    return result;
                }
            }
            return null;
        }
    }

    private void retriveAllChildProcessInstance(List<ProcessInstance> children, long parentId) {
        ProcessInstanceQuery query = processService.createProcessInstanceQuery();
        query.parentId(parentId);
        List<ProcessInstance> list = query.list();
        for (ProcessInstance pi : list) {
            retriveAllChildProcessInstance(children, pi.getId());
        }
        children.addAll(list);
    }

    public void addContextVariables(ProcessInstance processInstance, Map<String, Object> variables) {
        if (variables == null || variables.isEmpty()) {
            return;
        }
        CacheService cacheService = EnvironmentUtils.getEnvironment().getCache();
        ProcessMapContext context = cacheService.getContext(processInstance.getId());
        if (context == null) {
            buildProcessInstanceContext(processInstance);
            context = cacheService.getContext(processInstance.getId());
        }
        if (context == null) {
            throw new IllegalArgumentException("ProcessInstance [" + processInstance.getId() + "] expression context does not exist!");
        }
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            String key = entry.getKey();
            if (isSafeVariableKey(key)) {
                context.set(key, entry.getValue());
            } else {
                log.warn("Unsafe variable key detected in addContextVariables: " + key);
            }
        }
    }

    public void moveContextToParent(ProcessInstance processInstance) {
        long parentId = processInstance.getParentId();
        if (parentId < 1) {
            return;
        }
        CacheService cacheService = EnvironmentUtils.getEnvironment().getCache();
        ProcessMapContext parentContext = cacheService.getContext(parentId);
        if (parentContext == null) {
            buildProcessInstanceContext(processService.getProcessInstanceById(parentId));
            parentContext = cacheService.getContext(parentId);
        }
        if (parentContext == null) {
            throw new IllegalArgumentException("ProcessInstance " + parentId + " context does not exist!");
        }
        ProcessMapContext context = cacheService.getContext(processInstance.getId());
        if (context == null) {
            buildProcessInstanceContext(processInstance);
            context = cacheService.getContext(processInstance.getId());
        }
        if (context == null) {
            throw new IllegalArgumentException("ProcessInstance " + processInstance.getId() + " context does not exist!");
        }
        Map<String, Object> map = context.getMap();
        for (String key : map.keySet()) {
            parentContext.set(key, map.get(key));
        }
    }

    public void setProcessService(ProcessService processService) {
        this.processService = processService;
    }

    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        providers = applicationContext.getBeansOfType(ExpressionProvider.class).values();
    }

    public void initExpressionContext() {
        CacheService cacheService = EnvironmentUtils.getEnvironment().getCache();
        ProcessInstanceQuery query = processService.createProcessInstanceQuery();
        List<ProcessInstance> instances = query.list();
        for (ProcessInstance pi : instances) {
            ProcessMapContext context = new ProcessMapContext();
            for (Variable var : processService.getProcessVariables(pi.getId())) {
                context.set(var.getKey(), var.getValue());
            }
            cacheService.putContext(pi.getId(), context);
        }
    }

    public void afterPropertiesSet() throws Exception {
        // Optionally initialize the expression context at startup
        // initExpressionContext();
    }

    private boolean isSafeExpression(String expression) {
        // Whitelist: Only allow expressions that are pure numbers, simple variable names, or simple arithmetic expressions
        String[] allowedPatterns = new String[] {
            "^[0-9]+(\\.[0-9]+)?$",
            "^[a-zA-Z_][a-zA-Z0-9_]*$",
            "^(?:[a-zA-Z_][a-zA-Z0-9_]*|[0-9]+(\\.[0-9]+)?)(?:\\s*[+\\-*/]\\s*(?:[a-zA-Z_][a-zA-Z0-9_]*|[0-9]+(\\.[0-9]+)?))*$"
        };
        for (String pattern : allowedPatterns) {
            if (expression.matches(pattern)) {
                return true;
            }
        }
        log.warn("Expression does not match allowed safe patterns: " + expression);
        return false;
    }

    private boolean isSafeVariableKey(String key) {
        // Only allow keys that start with a letter or underscore, followed by letters, digits, or underscores
        return key != null && key.matches("^[a-zA-Z_][a-zA-Z0-9_]*$");
    }
}
