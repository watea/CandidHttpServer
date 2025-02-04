/*
 * Copyright (c) 2024. Stephane Treuchot
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to
 * do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package com.watea.candidhttpserver;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@SuppressWarnings("unused")
public class HttpServer {
  private static final String LOG_TAG = HttpServer.class.getSimpleName();
  private static final String END = "\r\n";
  private static final String HTTP = "HTTP/1.1 ";
  private static final String OK = HTTP + "200 OK" + END;
  private static final String NOT_FOUND = HTTP + "404 Not Found" + END + END;
  private static final String BAD_REQUEST = HTTP + "Bad Request" + END + END;
  private static final String SEPARATOR = ": ";
  @NonNull
  private final ServerSocket serverSocket;
  private final Set<Handler> handlers = new HashSet<>();
  private boolean isRunning = false;

  public HttpServer() throws IOException {
    serverSocket = new ServerSocket(0);
  }

  @NonNull
  private static byte[] getBytes(@NonNull String string) {
    return string.getBytes(StandardCharsets.UTF_8);
  }

  public void addHandler(@NonNull Handler handler) {
    handlers.add(handler);
  }

  public void start() {
    Log.d(LOG_TAG, "start");
    isRunning = true;
    new Thread(() -> {
      while (isRunning) {
        try {
          final Socket socket = serverSocket.accept();
          new Thread(() -> {
            handleClient(socket);
            try {
              socket.close();
            } catch (IOException iOException) {
              Log.e(LOG_TAG, "HttpServer failed to close socket received!", iOException);
            }
          }).start();
        } catch (IOException iOException) {
          if (isRunning) {
            Log.e(LOG_TAG, "HttpServer failed to create socket received!", iOException);
          }
        }
      }
      Log.d(LOG_TAG, "exit");
    }).start();
  }

  public void stop() throws IOException {
    Log.d(LOG_TAG, "stop");
    isRunning = false;
    serverSocket.close();
  }

  public int getListeningPort() {
    return serverSocket.getLocalPort();
  }

  private void handleClient(@NonNull Socket socket) {
    try (final BufferedReader reader =
           new BufferedReader(new InputStreamReader(socket.getInputStream()));
         final OutputStream responseStream = socket.getOutputStream()) {
      // Parse the request
      final Request request = parseRequest(reader);
      if (request == null) {
        responseStream.write(getBytes(BAD_REQUEST));
      } else {
        final Response response = new Response(responseStream);
        for (final Handler handler : handlers) {
          handler.handle(request, response, responseStream);
          if (response.isClientHandled()) {
            break;
          }
        }
        if (!response.isClientHandled()) {
          responseStream.write(getBytes(NOT_FOUND));
        }
      }
      responseStream.flush();
    } catch (IOException iOException) {
      Log.e(LOG_TAG, "handleClient: failed!", iOException);
    }
  }

  @Nullable
  private Request parseRequest(@NonNull BufferedReader reader) throws IOException {
    // Parse the request line
    final String requestLine = reader.readLine();
    if (requestLine == null || requestLine.isEmpty()) {
      return null;
    }
    final String[] requestParts = requestLine.split(" ");
    if (requestParts.length != 3) {
      return null;
    }
    final Request request = new Request(requestParts[0], requestParts[1], requestParts[2]);
    // Parse the headers
    String line;
    while (!(line = reader.readLine()).isEmpty()) {
      String[] headerParts = line.split(SEPARATOR, 2);
      if (headerParts.length == 2) {
        request.addHeader(headerParts[0], headerParts[1]);
      }
    }
    return request;
  }

  public interface Handler {
    void handle
      (@NonNull Request request,
       @NonNull Response response,
       @NonNull OutputStream responseStream) throws IOException;
  }

  public static class Request {
    @NonNull
    private final String method;
    @NonNull
    private final Uri uri;
    @NonNull
    private final String protocol;
    private final Map<String, String> headers = new HashMap<>();

    public Request(@NonNull String method, @NonNull String path, @NonNull String protocol) {
      this.method = method;
      uri = Uri.parse(path);
      this.protocol = protocol;
    }

    @NonNull
    public String getMethod() {
      return method;
    }

    @NonNull
    public String getPath() {
      assert uri.getPath() != null;
      return uri.getPath();
    }

    @NonNull
    public String getProtocol() {
      return protocol;
    }

    @NonNull
    public Map<String, String> getHeaders() {
      return headers;
    }

    public void addHeader(@NonNull String name, @NonNull String value) {
      headers.put(name, value);
    }

    @Nullable
    public String getParams(@NonNull String key) {
      return uri.getQueryParameter(key);
    }
  }

  public static class Response {
    public static final String CONTENT_TYPE = "Content-Type";
    public static final String CONTENT_LENGTH = "Content-Length";
    private final Map<String, String> headers = new HashMap<>();
    @NonNull
    private final OutputStream outputStream;
    private boolean isClientHandled = false;

    public Response(@NonNull OutputStream outputStream) {
      this.outputStream = outputStream;
    }

    public void addHeader(@NonNull String key, @NonNull String value) {
      headers.put(key, value);
    }

    public void send() throws IOException {
      outputStream.write(OK.getBytes(StandardCharsets.UTF_8));
      for (final String key : headers.keySet()) {
        outputStream.write(getBytes(key + SEPARATOR + headers.get(key) + END));
      }
      outputStream.write(getBytes(END));
      isClientHandled = true;
    }

    public boolean isClientHandled() {
      return isClientHandled;
    }
  }
}