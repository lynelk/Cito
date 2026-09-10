package net.citotech.cito.Model;

import java.util.Map;

/**
 * @author josephtabajjwa
 */
public class HttpRequestResponse {
    int statusCode;
    String response;
    String requestData = "";
    String url;
    Map<String, String> requestHeaders;
    Map<String, String> responseHeaders;
    String errorMessage;

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getRequestData() {
        return requestData;
    }

    public void setRequestData(String requestData) {
        this.requestData = requestData;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(int statusCode) {
        this.statusCode = statusCode;
    }

    public String getResponse() {
        return response;
    }

    public void setResponse(String response) {
        this.response = response;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Map<String, String> getRequestHeaders() {
        return requestHeaders;
    }

    public void setRequestHeaders(Map<String, String> requestHeaders) {
        this.requestHeaders = requestHeaders;
    }

    public Map<String, String> getResponseHeaders() {
        return responseHeaders;
    }

    public void setResponseHeaders(Map<String, String> responseHeaders) {
        this.responseHeaders = responseHeaders;
    }

    /** Diagnostics deliberately exclude credentials, payloads, URLs and exception messages. */
    @Override
    public String toString() {
        return "httpStatus="
                + statusCode
                + "; transportError="
                + (errorMessage != null && !errorMessage.isBlank());
    }

    public class Header {
        String name;
        String value;

        public Header(String name, String value) {
            this.name = name;
            this.value = value;
        }

        public String getValue() {
            return this.name;
        }

        public String getName() {
            return this.name;
        }
    }
}
