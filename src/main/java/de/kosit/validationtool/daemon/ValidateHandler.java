/*
 * Copyright 2017-2022  Koordinierungsstelle für IT-Standards (KoSIT)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.kosit.validationtool.daemon;

import com.sun.net.httpserver.HttpExchange;
import de.kosit.validationtool.api.InputFactory;
import de.kosit.validationtool.api.Result;
import de.kosit.validationtool.impl.Printer;
import de.kosit.validationtool.impl.input.SourceInput;
import de.kosit.validationtool.impl.input.StreamHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.saxon.s9api.*;
import org.mustangproject.validator.ZUGFeRDValidator;
import org.w3c.dom.Document;

import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.stream.StreamSource;
import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@RequiredArgsConstructor
public class ValidateHandler extends BaseHandler {

    private static final AtomicLong counter = new AtomicLong(0);

    @Override
    public void handle(final HttpExchange httpExchange) throws IOException {
        try {
            // Get the content type from the headers
            String contentType = httpExchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType != null && contentType.contains("multipart/form-data")) {
                // Process the file upload
                InputStream inputStream = httpExchange.getRequestBody();
                byte[] fileBytes = inputStream.readAllBytes();
                // Extract file data (naive parsing, assuming single file upload)
                String boundary = contentType.split("boundary=")[1];
                String[] parts = new String(fileBytes).split("--" + boundary);

                for (String part : parts) {
                    if (part.contains("Content-Disposition")) {
                        String[] lines = part.split("\r\n");
                        for (String line : lines) {
                            if (line.contains("filename=")) {
                                // Extract the file name
                                String fileName = line.split("filename=")[1].replaceAll("\"", "");

                                if (fileName.contains(".xml")) {
                                    // Write the file to the disk
                                    byte[] fileData = part.substring(part.indexOf("\r\n\r\n") + 4).getBytes();
                                    File file = new File("uploads/" + fileName);

                                    try ( FileOutputStream fos = new FileOutputStream(file) ) {
                                        fos.write(fileData);
                                    } catch (Exception ex) {
                                        error(httpExchange, HttpStatus.SC_INTERNAL_SERVER_ERROR, ex.getMessage());
                                    }
                                    ByteArrayInputStream bais = new ByteArrayInputStream(fileData);
                                    // Validate using the new ByteArrayInputStream
                                    ZUGFeRDValidator zfv = new ZUGFeRDValidator();
                                    String validationResult = zfv.validate(bais, fileName);

                                    // Send a response
                                    write(httpExchange, validationResult.getBytes(), APPLICATION_XML, HttpStatus.SC_OK);
                                } else if (fileName.contains(".pdf")) {
                                    Path destination = Path.of("uploads/" + fileName);
                                    Files.write(destination, fileBytes);
                                    // Validate using the new ByteArrayInputStream
                                    ZUGFeRDValidator zfv = new ZUGFeRDValidator();
                                    String validationResult = zfv.validate(fileBytes, fileName);
                                    // Send a response
                                    write(httpExchange, validationResult.getBytes(), APPLICATION_XML, HttpStatus.SC_OK);
                                } else {
                                    error(httpExchange, HttpStatus.SC_NOT_ACCEPTABLE,
                                            "Invalid content type, expecting multipart/form-data.");
                                }

                            }
                        }
                    }
                }
            } else {
                error(httpExchange, HttpStatus.SC_NOT_ACCEPTABLE, "Invalid content type, expecting multipart/form-data.");
            }
        } catch (final Exception e) {
            Printer.writeOut("Error TransformHandler", e);
            error(httpExchange, HttpStatus.SC_INTERNAL_SERVER_ERROR, "Internal error: " + e.getMessage());
        }
    }

    private static String resolveInputName(final URI requestURI) {
        final String path = requestURI.getPath();
        if (path.equalsIgnoreCase("/")) {
            return "supplied_instance_" + counter.incrementAndGet();
        }
        return path.substring((path.lastIndexOf('/') + 1));
    }

    private static int resolveStatus(final Result result) {
        if (result.isProcessingSuccessful()) {
            return result.isAcceptable() ? HttpStatus.SC_OK : HttpStatus.SC_NOT_ACCEPTABLE;
        }
        return HttpStatus.SC_UNPROCESSABLE_ENTITY;
    }
}
