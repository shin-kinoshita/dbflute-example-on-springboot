/*
 * Copyright 2015-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.lastaflute.remoteapi;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;

import org.dbflute.helper.beans.DfBeanDesc;
import org.dbflute.helper.beans.DfPropertyDesc;
import org.dbflute.helper.beans.factory.DfBeanDescFactory;
import org.dbflute.helper.message.ExceptionMessageBuilder;
import org.dbflute.optional.OptionalThing;
import org.dbflute.remoteapi.FlutyRemoteApi;
import org.dbflute.remoteapi.FlutyRemoteApiRule;
import org.dbflute.remoteapi.exception.RemoteApiRequestValidationErrorException;
import org.dbflute.remoteapi.exception.RemoteApiResponseValidationErrorException;
import org.dbflute.remoteapi.logging.SendReceiveLogger;
import org.dbflute.util.Srl;
import org.lastaflute.core.util.Lato;
import org.lastaflute.web.servlet.request.RequestManager;
import org.lastaflute.web.validation.Required;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author jflute
 */
public class LastaRemoteApi extends FlutyRemoteApi {

    // ===================================================================================
    //                                                                          Definition
    //                                                                          ==========
    private static final Logger logger = LoggerFactory.getLogger(LastaRemoteApi.class);

    // ===================================================================================
    //                                                                           Attribute
    //                                                                           =========
    protected RequestManager requestManager; // not null after set, for validation and various purpose

    // ===================================================================================
    //                                                                         Constructor
    //                                                                         ===========
    public LastaRemoteApi(Consumer<FlutyRemoteApiRule> defaultOpLambda, Object callerExp) {
        super(defaultOpLambda, callerExp);
    }

    public void acceptRequestManager(RequestManager requestManager) {
        this.requestManager = requestManager;
    }

    // ===================================================================================
    //                                                                          Validation
    //                                                                          ==========
    @Override
    protected void validateParam(Type returnType, String urlBase, String actionPath, Object[] pathVariables, Object param,
            FlutyRemoteApiRule rule) {
        if (rule.getValidatorOption().isSuppressParam()) {
            return;
        }
        try {
            doValidate(param);
        } catch (RemoteApiSendReceiveValidationErrorException e) {
            final ExceptionMessageBuilder br = new ExceptionMessageBuilder();
            br.addNotice("Validation Error as Param object for the remote API.");
            final String url = urlBase + actionPath + "/" + Arrays.asList(pathVariables);
            setupRequestInfo(br, returnType, url, param);
            setupYourRule(br, rule);
            final String msg = br.buildExceptionMessage();
            if (rule.getValidatorOption().isHandleAsWarnParam()) {
                logger.warn(msg, e);
            } else {
                throw new RemoteApiRequestValidationErrorException(msg, e);
            }
        }
    }

    @Override
    protected void validateReturn(Type returnType, String url, OptionalThing<Object> param, int httpStatus, OptionalThing<String> body,
            Object ret, FlutyRemoteApiRule rule) {
        try {
            doValidate(ret);
        } catch (RemoteApiSendReceiveValidationErrorException e) {
            final ExceptionMessageBuilder br = new ExceptionMessageBuilder();
            br.addNotice("Validation Error as Return object for the remote API.");
            setupRequestInfo(br, returnType, url, param);
            setupResponseInfo(br, httpStatus, body);
            setupReturnInfo(br, ret);
            setupYourRule(br, rule);
            final String msg = br.buildExceptionMessage();
            if (rule.getValidatorOption().isHandleAsWarnReturn()) {
                logger.warn(msg, e);
            } else {
                throw new RemoteApiResponseValidationErrorException(msg, e);
            }
        }
    }

    protected void doValidate(Object ret) {
        final LinkedList<String> pathList = new LinkedList<String>();
        pathList.add("");
        doValidate(ret, pathList);
    }

    protected void doValidate(Object bean, LinkedList<String> pathList) {
        if (bean instanceof Iterable<?>) {
            @SuppressWarnings("unchecked")
            final Iterable<Object> itera = (Iterable<Object>) bean;
            int index = 0;
            for (Object element : itera) {
                String last = pathList.removeLast();
                pathList.addLast((last.isEmpty() ? "list" : last) + "[" + index + "]");
                actuallyValidate(element, pathList);
                pathList.removeLast(); // remove last element with "[]"
                pathList.addLast(last); // restore last element for next loop
                ++index;
            }
        } else {
            actuallyValidate(bean, pathList);
        }
    }

    protected void actuallyValidate(Object bean, LinkedList<String> pathList) {
        final DfBeanDesc beanDesc = DfBeanDescFactory.getBeanDesc(bean.getClass());
        for (String proppertyName : beanDesc.getProppertyNameList()) {
            final DfPropertyDesc propertyDesc = beanDesc.getPropertyDesc(proppertyName);
            final Field field = propertyDesc.getField();
            if (field == null) {
                continue; // field property only supported
            }
            pathList.add(field.getName());
            if (field.getAnnotation(NotNull.class) != null) {
                checkNotNullProperty(bean, pathList, propertyDesc);
            }
            if (field.getAnnotation(Required.class) != null) {
                checkRequiredProperty(bean, pathList, propertyDesc);
            }
            final Valid validAnno = field.getAnnotation(Valid.class);
            if (validAnno != null) {
                final Object nestedInstance = propertyDesc.getValue(bean);
                if (nestedInstance != null) {
                    doValidate(nestedInstance, pathList);
                }
            }
            pathList.removeLast();
        }
    }

    protected void checkNotNullProperty(Object bean, LinkedList<String> pathList, DfPropertyDesc propertyDesc) {
        final Object value = propertyDesc.getValue(bean);
        if (value == null) {
            String msg = "The field is not null but null value: field=" + buildFieldPath(pathList);
            throw new RemoteApiSendReceiveValidationErrorException(msg);
        }
    }

    protected void checkRequiredProperty(Object bean, LinkedList<String> pathList, final DfPropertyDesc propertyDesc) {
        final Object value = propertyDesc.getValue(bean);
        if (value == null || isRequiredStringBadValue(value) || isRequiredListBadValue(value)) {
            String msg = "The field is required but no value: value=" + value + ", field=" + buildFieldPath(pathList);
            throw new RemoteApiSendReceiveValidationErrorException(msg);
        }
    }

    protected boolean isRequiredStringBadValue(Object value) {
        return value instanceof String && ((String) value).trim().isEmpty();
    }

    protected boolean isRequiredListBadValue(Object value) {
        return value instanceof List && ((List<?>) value).isEmpty();
    }

    protected String buildFieldPath(LinkedList<String> pathList) {
        return Srl.ltrim(pathList.stream().collect(Collectors.joining(".")), ".");
    }

    public static class RemoteApiSendReceiveValidationErrorException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public RemoteApiSendReceiveValidationErrorException(String msg) {
            super(msg);
        }
    }

    // ===================================================================================
    //                                                                      RemoteApi Rule
    //                                                                      ==============
    @Override
    protected FlutyRemoteApiRule newRemoteApiRule() {
        return new LastaRemoteApiRule();
    }

    // ===================================================================================
    //                                                                Send/Receive Logging
    //                                                                ====================
    @Override
    protected SendReceiveLogger createSendReceiveLogger() { // may be overridden
        return super.createSendReceiveLogger().asTopKeyword("lastaflute"); // for future migration
    }

    // ===================================================================================
    //                                                                      Error Handling
    //                                                                      ==============
    @Override
    protected String convertBeanToDebugString(Object bean) {
        return Lato.string(bean); // because its toString() may not be overridden
    }
}
