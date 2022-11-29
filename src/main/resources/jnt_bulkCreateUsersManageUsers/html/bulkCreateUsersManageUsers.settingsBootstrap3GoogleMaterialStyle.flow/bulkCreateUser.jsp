<%@ page language="java" contentType="text/html;charset=UTF-8" %>
<%@ taglib prefix="template" uri="http://www.jahia.org/tags/templateLib" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions" %>
<%@ taglib prefix="jcr" uri="http://www.jahia.org/tags/jcr" %>
<%@ taglib prefix="fmt" uri="http://java.sun.com/jsp/jstl/fmt" %>
<%@ taglib prefix="functions" uri="http://www.jahia.org/tags/functions" %>
<%@ taglib prefix="user" uri="http://www.jahia.org/tags/user" %>
<%--@elvariable id="currentNode" type="org.jahia.services.content.JCRNodeWrapper"--%>
<%--@elvariable id="out" type="java.io.PrintWriter"--%>
<%--@elvariable id="script" type="org.jahia.services.render.scripting.Script"--%>
<%--@elvariable id="scriptInfo" type="java.lang.String"--%>
<%--@elvariable id="workspace" type="java.lang.String"--%>
<%--@elvariable id="renderContext" type="org.jahia.services.render.RenderContext"--%>
<%--@elvariable id="currentResource" type="org.jahia.services.render.Resource"--%>
<%--@elvariable id="url" type="org.jahia.services.render.URLGenerator"--%>
<%--@elvariable id="mailSettings" type="org.jahia.services.mail.MailSettings"--%>
<%--@elvariable id="flowRequestContext" type="org.springframework.webflow.execution.RequestContext"--%>
<%--@elvariable id="flowExecutionUrl" type="java.lang.String"--%>
<template:addResources type="javascript" resources="jquery.min.js,jquery-ui.min.js,jquery.blockUI.js,workInProgress.js"/>
<fmt:message key="label.workInProgressTitle" var="i18nWaiting"/><c:set var="i18nWaiting" value="${functions:escapeJavaScript(i18nWaiting)}"/>
<template:addResources>
    <script type="text/javascript">
        $(document).ready(function() {
            $('#${currentNode.identifier}-confirm').click(function() {workInProgress('${i18nWaiting}');});
        });
    </script>
</template:addResources>

<div class="page-header">
    <h2><fmt:message key="bulk-create-users.users.bulk.create"/></h2>
</div>

<c:forEach items="${flowRequestContext.messageContext.allMessages}" var="message">
    <c:if test="${message.severity eq 'ERROR'}">
        <div class="alert alert-danger">
            <button type="button" class="close" data-dismiss="alert">&times;</button>
                ${message.text}
        </div>
    </c:if>
    <c:if test="${message.severity eq 'INFO'}">
        <div class="alert alert-success">
            <button type="button" class="close" data-dismiss="alert">&times;</button>
                ${message.text}
        </div>
    </c:if>
</c:forEach>

<div class="row">
    <div class="col-md-offset-2 col-md-8">
        <div class="panel panel-default">
            <div class="panel-body">

                <p><fmt:message key="bulk-create-users.users.batch.file.format"/></p>

                <form action="${flowExecutionUrl}" method="post" enctype="multipart/form-data" autocomplete="off">
                    <div class="form-group label-floating is-fileinput">
                        <input type="file" name="csvFile" id="csvFile"/>
                        <div class="input-group">
                            <span class="input-group-addon"><i class="material-icons material-icons-small">touch_app</i></span>
                            <label class="control-label" for="csvFile"><fmt:message key="label.csvFile"/> <strong class="text-danger">*</strong></label>
                            <input class="form-control" type="text" readonly>
                        </div>
                        <span class="material-input"></span>
                    </div>

                    <div class="form-group label-floating">
                        <label class="control-label" for="csvSeparator"><fmt:message key="label.csvSeparator"/></label>
                        <input class="form-control" type="text" name="csvSeparator" value="${csvFile.csvSeparator}" id="csvSeparator"/>
                    </div>

                    <div class="form-group">
                        <button class="btn btn-primary btn-raised pull-right" type="submit" name="_eventId_confirm" id="${currentNode.identifier}-confirm">
                            <fmt:message key="label.import"/>
                        </button>
                        <button class="btn btn-default pull-right" type="submit" name="_eventId_cancel">
                            <fmt:message key="label.cancel"/>
                        </button>
                    </div>
                </form>
            </div>
        </div>
    </div>
</div>
