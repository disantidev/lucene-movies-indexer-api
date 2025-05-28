package com.disantidev.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.sun.net.httpserver.HttpServer;

public class App {

    public static void main(String[] args) throws IOException, ParseException {
        HttpServer server = HttpServer.create(new InetSocketAddress(3000), 0);

        Path directoryPath = Files.createTempDirectory("moviesIndex");
        Directory directory = FSDirectory.open(directoryPath);

        Analyzer analyzer = new StandardAnalyzer();

        server.createContext("/import", exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, 0);
                exchange.getResponseBody().close();
                return;
            }

            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.startsWith("multipart/form-data")) {
                exchange.sendResponseHeaders(400, 0);
                exchange.getResponseBody().write("Content-Type must be multipart/form-data".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().close();
                return;
            }

            String boundary = null;
            String[] params = contentType.split(";");
            for (String param : params) {
                param = param.trim();
                if (param.startsWith("boundary=")) {
                    boundary = param.substring("boundary=".length());
                    if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
                        boundary = boundary.substring(1, boundary.length() - 1);
                    }
                }
            }
            if (boundary == null) {
                exchange.sendResponseHeaders(400, 0);
                exchange.getResponseBody().write("Missing boundary in Content-Type".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().close();
                return;
            }

            InputStream requestBody = exchange.getRequestBody();
            StringBuilder jsonContent = new StringBuilder();
            boolean fileFound = false;

            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(requestBody, StandardCharsets.UTF_8));
                String line;
                boolean inFile = false;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("--" + boundary)) {
                        inFile = false;
                    }
                    if (line.contains("Content-Disposition: form-data") && line.contains("filename=")) {
                        inFile = true;
                        fileFound = true;
                        while ((line = reader.readLine()) != null && !line.isEmpty()) {}
                        continue;
                    }
                    if (inFile && !line.startsWith("--" + boundary)) {
                        jsonContent.append(line).append("\n");
                    }
                }
            } catch (IOException e) {
                exchange.sendResponseHeaders(500, 0);
                exchange.getResponseBody().write("Failed to read uploaded file".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().close();
                return;
            }

            if (!fileFound || jsonContent.length() == 0) {
                exchange.sendResponseHeaders(400, 0);
                exchange.getResponseBody().write("No file uploaded or file is empty".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().close();
                return;
            }

            IndexWriterConfig config = new IndexWriterConfig(analyzer);

            try (IndexWriter writer = new IndexWriter(directory, config)) {
                try {
                    JSONObject jsonObject = new JSONObject(jsonContent.toString());

                    JSONArray results = jsonObject.getJSONArray("results");

                    for (int i = 0; i < results.length(); i++) {
                        JSONObject result = results.getJSONObject(i);

                        String title = result.getString("title");
                        String overview = result.getString("overview");

                        Document doc = new Document();

                        doc.add(new Field("title", title, TextField.TYPE_STORED));
                        doc.add(new Field("overview", overview, TextField.TYPE_STORED));

                        writer.addDocument(doc);
                    }

                    JSONObject result = new JSONObject();
                    result.append("inserted", 0);

                    String resultContent = String.format(
                        "{ \"success\": %b, \"data\": { \"inserted\": %,d } }",
                        true,
                        results.length()
                    );

                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(201, 0);
                    exchange.getResponseBody().write(resultContent.getBytes());
                } catch (JSONException e) {
                    System.err.println(e.getMessage());
                    exchange.sendResponseHeaders(500, 0);
                } finally {
                    exchange.getResponseBody().close();
                }
            }
        });

        server.createContext("/search", exchange -> {
            String queryString = exchange.getRequestURI().getQuery();

            if (queryString.isEmpty()) {
                exchange.sendResponseHeaders(400, 0);
                exchange.getResponseBody().write("query params was not provided".getBytes());
                exchange.getResponseBody().close();
                return;
            }

            String[] queryPairs = queryString.split("&");

            Map<String, String> queryParams = new LinkedHashMap<>();

            for (String queryPair : queryPairs) {
                String[] pair = queryPair.split("=", 2);
                String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                String value = pair.length > 1 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
                queryParams.put(key, value);
            }

            String search = queryParams.get("q");

            if (search == null || search.isEmpty()) {
                exchange.sendResponseHeaders(400, 0);
                exchange.getResponseBody().write("\"q\" param is empty".getBytes());
                exchange.getResponseBody().close();
                return;
            }

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                IndexSearcher searcher = new IndexSearcher(reader);

                String[] fields = {"title", "overview"};

                Map<String, Float> boosts = new HashMap<>();
                boosts.put("title", 2.f);
                boosts.put("overview", 1.f);

                MultiFieldQueryParser parser = new MultiFieldQueryParser(fields, analyzer, boosts);

                try {
                    Query query = parser.parse(search);

                    ScoreDoc[] scoreDocs = searcher.search(query, 10).scoreDocs;

                    StoredFields storedFields = searcher.storedFields();

                    JSONArray responseArray = new JSONArray();

                    for (ScoreDoc scoreDoc : scoreDocs) {
                        Document doc = storedFields.document(scoreDoc.doc);
                        JSONObject obj = new JSONObject();

                        obj.put("title", doc.get("title"));
                        obj.put("overview", doc.get("overview"));

                        responseArray.put(obj);
                    }

                    String jsonContent = String.format(
                        "{ \"success\": %b, \"data\": %s }",
                        true,
                        responseArray.toString()
                    );

                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write(jsonContent.getBytes());
                } catch (org.apache.lucene.queryparser.classic.ParseException e) {
                    exchange.sendResponseHeaders(400, 0);
                    exchange.getResponseBody().write(e.getMessage().getBytes());
                } finally {
                    exchange.getResponseBody().close();
                }

            }

        });

        server.start();
    }
}
