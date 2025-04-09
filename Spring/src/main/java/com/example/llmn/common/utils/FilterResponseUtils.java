package com.example.llmn.common.utils;

import com.example.llmn.common.exceptions.CustomException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public class FilterResponseUtils {

    private FilterResponseUtils() {
    }

    public static void unAuthorized(HttpServletResponse resp, CustomException e) throws IOException {
        forbidden(resp, e);
    }

    public static void forbidden(HttpServletResponse resp, CustomException e) throws IOException {
        resp.setStatus(e.status().value());
        resp.setContentType("application/json; charset=utf-8");
        ObjectMapper om = new ObjectMapper();
        String responseBody = om.writeValueAsString(e.body());
        resp.getWriter().println(responseBody);
    }
}