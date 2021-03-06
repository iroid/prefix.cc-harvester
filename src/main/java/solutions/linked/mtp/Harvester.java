/*
 * The MIT License
 *
 * Copyright 2019 FactsMission AG.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package solutions.linked.mtp;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import org.apache.clerezza.commons.rdf.Graph;
import org.apache.clerezza.commons.rdf.IRI;
import org.apache.clerezza.commons.rdf.impl.utils.simple.SimpleGraph;
import org.apache.clerezza.rdf.core.serializedform.Parser;
import org.apache.clerezza.rdf.core.serializedform.Serializer;
import org.apache.clerezza.rdf.ontologies.OWL;
import org.apache.clerezza.rdf.ontologies.RDF;
import org.apache.clerezza.rdf.ontologies.RDFS;
import org.apache.clerezza.rdf.utils.GraphNode;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.wymiwyg.commons.util.arguments.ArgumentHandler;

/**
 *
 * @author user
 */
public class Harvester {

    private final Arguments arguments;
    private final Path storageDir;
    private final Parser parser = Parser.getInstance();
    private final Serializer serializer = Serializer.getInstance();
    final HttpClientBuilder hcb = HttpClientBuilder.create();
    private final IRI NAMESPACE_PREFIX = new IRI("http://purl.org/vocab/vann/preferredNamespacePrefix");
    private final IRI NAMESPACE_URI = new IRI("http://purl.org/vocab/vann/preferredNamespaceUri");
    private final IRI VOID_TRIPLES = new IRI("http://rdfs.org/ns/void#triples");

    public Harvester(Arguments arguments) throws IOException {
        this.arguments = arguments;
        storageDir = Path.of(arguments.output());
        Files.createDirectories(storageDir);
    }

    public static void main(final String... args) throws Exception {
        Arguments arguments = ArgumentHandler.readArguments(Arguments.class, args);
        if (arguments != null) {
            new Harvester(arguments).start();
        }
    }

    public void start() throws Exception {
        Graph index = new SimpleGraph(loadGraph(arguments.index(), arguments.base()));
        GraphNode node = new GraphNode(OWL.Ontology, index);
        Iterator<GraphNode> ontologyIter = node.getSubjectNodes(RDF.type);
        List<GraphNode> ontologies = new ArrayList<>();
        while (ontologyIter.hasNext()) {
            GraphNode ontologyDescription = ontologyIter.next();
            ontologies.add(ontologyDescription);
        }
        int count = 0;
        for (GraphNode ontologyDescription : ontologies) {
            final String namespaceUri = ontologyDescription.getLiterals(NAMESPACE_URI).next().getLexicalForm();
            final String namespacePrefix = ontologyDescription.getLiterals(NAMESPACE_PREFIX).next().getLexicalForm();
            try {
                Graph ontology = loadGraph(namespaceUri);
                save(ontology, namespacePrefix);
                ontologyDescription.addPropertyValue(VOID_TRIPLES, ontology.size());
            } catch (IOException | RuntimeException e) {
                String message = e.getMessage();
                System.err.println("Error processing "+namespaceUri+": "+message);
                if (message != null) {
                    ontologyDescription.addPropertyValue(RDFS.comment, message);
                } else {
                    ontologyDescription.addPropertyValue(RDFS.comment, e.getClass().toString());
                }
            } 
            ontologyDescription.replaceWith(new IRI(arguments.base()+namespacePrefix));
            System.out.println("Processed "+(++count)+" of "+ontologies.size());
            if ((count % 50) == 0) {
                save(index, "index");
            }
        }
        save(index, "index");

    }
    private Graph loadGraph(String uri) throws IOException {
        return loadGraph(uri, uri);
    }
    
    private Graph loadGraph(String uri, String base) throws IOException {
        if (arguments.verbose()) {
            System.out.println("Loading "+uri);
        }
        /*SSLContext sslContext = new SSLContextBuilder().loadTrustMaterial(null, new TrustStrategy() {
            @Override
            public boolean isTrusted(X509Certificate[] arg0, String arg1) throws CertificateException {
                return true;
            }
        }).build();
        hcb.setSSLContext(sslContext);*/
        try (CloseableHttpClient httpClient = hcb.build()) {
            final HttpGet httpGet = new HttpGet(uri);
            int hardTimeout = 60; // seconds
            TimerTask task = new TimerTask() {
                @Override
                public void run() {
                    if (httpGet != null) {
                        httpGet.abort();
                    }
                }
            };
            new Timer(true).schedule(task, hardTimeout * 1000);
            httpGet.setHeader("Accept", "text/turtle, application/rdf+xml, application/ld+json, application/n-triples, */*");
            final int timeout = 10 * 1000;
            httpGet.setConfig(RequestConfig.custom()
                    .setSocketTimeout(timeout)
                    .setConnectTimeout(timeout)
                    .setConnectionRequestTimeout(timeout)
                    .build());
            try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                HttpEntity entity = response.getEntity();
                final Header contentTypeHeader = entity.getContentType();
                String contentType = contentTypeHeader != null ? 
                        normalizeMediaType(contentTypeHeader.getValue()) :
                        "application/rdf+xml";
                return parser.parse(entity.getContent(), contentType, new IRI(base));
            }
        }
    }

    private void save(Graph graph, String slug) throws IOException {
        Path file = storageDir.resolve(slug + ".ttl");
        try (OutputStream outputStream = Files.newOutputStream(file)) {
            serializer.serialize(outputStream, graph, "application/n-triples");// "text/turtle");
        }
    }

    private String normalizeMediaType(String value) {
        String[] parts = value.split(";");
        String type = parts[0].trim();
        if ("text/xml".equals(type) || "application/xml".equals(type) || "application/octet-stream".equals(type)) {
            return "application/rdf+xml";
        }
        if ("text/n3".equals(type)) {
            return "text/turtle";
        }
        return type;
    }

}
