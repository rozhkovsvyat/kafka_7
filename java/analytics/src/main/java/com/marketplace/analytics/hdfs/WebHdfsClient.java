package com.marketplace.analytics.hdfs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP-клиент для взаимодействия с HDFS через WebHDFS REST API.
 * Обрабатывает 307-редиректы на DataNode и исправляет hostname
 * при работе с Docker-контейнерами (DataNode недоступен по внутреннему имени с хоста).
 */
@Component
public class WebHdfsClient {

    @Value("${hdfs.webhdfs-url}")
    private String baseUrl;

    private final ObjectMapper mapper = new ObjectMapper();

    public void mkdirs(String path) throws IOException {
        URL url = new URL(baseUrl + "/webhdfs/v1" + path + "?op=MKDIRS");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("PUT");
        connection.getResponseCode();
        connection.disconnect();
    }

    public void write(String path, String content) throws IOException {
        // Шаг 1: получить redirect URL
        URL url = new URL(baseUrl + "/webhdfs/v1" + path + "?op=CREATE&overwrite=true");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("PUT");
        connection.setDoOutput(true);
        int status = connection.getResponseCode();

        if (status == 307) {
            String location = fixRedirectUrl(connection.getHeaderField("Location"));
            connection.disconnect();
            // Шаг 2: записать данные по redirect URL
            HttpURLConnection writeConnection = (HttpURLConnection) new URL(location).openConnection();
            writeConnection.setRequestMethod("PUT");
            writeConnection.setDoOutput(true);
            writeConnection.setRequestProperty("Content-Type", "application/octet-stream");
            try (OutputStream outputStream = writeConnection.getOutputStream()) {
                outputStream.write(content.getBytes(StandardCharsets.UTF_8));
            }
            writeConnection.getResponseCode();
            writeConnection.disconnect();
        } else {
            connection.disconnect();
            throw new IOException("Expected 307 redirect, got: " + status);
        }
    }

    public List<String> listFileNames(String path) throws IOException {
        URL url = new URL(baseUrl + "/webhdfs/v1" + path + "?op=LISTSTATUS");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");

        List<String> names = new ArrayList<>();
        try (InputStream inputStream = connection.getInputStream()) {
            JsonNode root = mapper.readTree(inputStream);
            for (JsonNode fileStatus : root.path("FileStatuses").path("FileStatus")) {
                String name = fileStatus.path("pathSuffix").asText();
                if (name.startsWith("part-") && !name.endsWith(".crc")) {
                    names.add(path + "/" + name);
                }
            }
        }
        connection.disconnect();
        return names;
    }

    private String fixRedirectUrl(String location) {
        try {
            String namenodeHost = new URL(baseUrl).getHost();
            URL locationUrl = new URL(location);
            return new URL(locationUrl.getProtocol(), namenodeHost, locationUrl.getPort(), locationUrl.getFile()).toString();
        } catch (Exception ex) {
            return location;
        }
    }

    public String read(String path) throws IOException {
        // Шаг 1: получить redirect URL
        URL url = new URL(baseUrl + "/webhdfs/v1" + path + "?op=OPEN");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("GET");
        int status = connection.getResponseCode();

        if (status == 307) {
            String location = fixRedirectUrl(connection.getHeaderField("Location"));
            connection.disconnect();
            HttpURLConnection readConnection = (HttpURLConnection) new URL(location).openConnection();
            readConnection.setRequestMethod("GET");
            try (InputStream inputStream = readConnection.getInputStream()) {
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            } finally {
                readConnection.disconnect();
            }
        }
        connection.disconnect();
        throw new IOException("Expected 307 redirect, got: " + status);
    }
}
