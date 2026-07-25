package com.jcoder.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;

public class ConfigManager {

    private static final String CONFIG_FILE = "application.json";

    public final static AppConfig appConfig ;

    static{
        ObjectMapper mapper = new ObjectMapper();

        InputStream in = ConfigManager.class.getClassLoader().getResourceAsStream(CONFIG_FILE);
        try {
             appConfig = mapper.readValue(in,AppConfig.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }
}