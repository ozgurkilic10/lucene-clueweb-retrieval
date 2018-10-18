package edu.anadolu.cmdline;

import edu.anadolu.analysis.Tag;
import edu.anadolu.datasets.Collection;
import edu.anadolu.datasets.CollectionFactory;
import edu.anadolu.datasets.DataSet;
import edu.anadolu.eval.Evaluator;
import edu.anadolu.knn.Measure;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.CommonParams;
import org.clueweb09.InfoNeed;
import org.clueweb09.tracks.Track;
import org.kohsuke.args4j.Option;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static edu.anadolu.eval.Evaluator.discoverTextFiles;
import static org.apache.solr.common.params.CommonParams.HEADER_ECHO_PARAMS;
import static org.apache.solr.common.params.CommonParams.OMIT_HEADER;
import static org.clueweb09.tracks.Track.whiteSpaceSplitter;

/**
 * Tool for Sampling phase of learning to rank.
 * Saves sample list of 8 models decorated with relevance labels.
 * 0 is used for un-judged documents.
 * 0 is used for spam documents, RankLib cannot handle label of -2.
 */
public class SampleTool extends CmdLineTool {

    @Option(name = "-collection", required = true, usage = "underscore separated collection values", metaVar = "CW09A_CW12B")
    protected Collection collection;

    @Override
    public String getShortDescription() {
        return "result list judgements";
    }

    @Override
    public String getHelp() {
        return "Following properties must be defined in config.properties for " + CLI.CMD + " " + getName() + " tfd.home";
    }

    @Option(name = "-metric", required = false, usage = "Effectiveness measure")
    protected Measure measure = Measure.NDCG1000;

    @Option(name = "-tag", metaVar = "[KStem KStemAnchor]", required = false, usage = "Index Tag")
    protected String tag = Tag.KStem.toString();


    @Option(name = "-spam", metaVar = "[10|15|...|85|90]", required = false, usage = "Non-negative integer spam threshold")
    protected int spam = 0;

    @Option(name = "-task", required = false, usage = "task to be executed")
    private String task;

    /**
     * Strips : from the elements of the array
     *
     * @param src    "0 qid:1 1:3 2:3 3:81 4:36.48606 5:22.28823 6:24.98682 7:11.43550 8:17.87569"
     * @param srcPos 4
     * @return "81 36.48606 22.28823 24.98682 11.43550 17.87569"
     */
    private static String[] copy(String[] src, int srcPos) {

        String[] copy = new String[src.length - srcPos];

        for (int i = srcPos; i < src.length; i++) {

            int j = src[i].indexOf(":");

            if (j == -1) {
                throw new RuntimeException("cannot find : in " + src[i]);
            }

            copy[i - srcPos] = src[i].substring(j + 1);
        }

        return copy;
    }

    private void merge(Path path) {

        for (String model : new String[]{"DPH", "DFIC", "DFRee", "PL2", "DLH13", "BM25", "LGD", "Dirichlet"}) {

            try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(path.resolve(model + ".features"), StandardCharsets.US_ASCII))) {

                Map<String, List<String>> map = new LinkedHashMap<>();

                int len = -1;
                for (String field : new String[]{"whole", "anchor", "body", "title", "url"}) {
                    List<String> lines = Files.readAllLines(path.resolve(model + "_" + field + ".features"), StandardCharsets.US_ASCII);
                    map.put(field, lines);

                    if (len != -1) {
                        if (len != lines.size()) throw new RuntimeException("lines size does not match!");
                    }
                    len = lines.size();

                }

                for (int j = 0; j < len; j++) {
                    StringBuilder builder = new StringBuilder();
                    String DOC_ID = null, Q_ID = null;
                    int f = 0;
                    for (String field : new String[]{"whole", "anchor", "body", "title", "url"}) {
                        String line = map.get(field).get(j);

                        int i = line.indexOf("#");

                        if (i == -1) {
                            throw new RuntimeException("cannot find # in " + line);
                        }

                        String p1 = line.substring(0, i).trim();
                        String docId = line.substring(i + 1).trim();
                        String[] parts = whiteSpaceSplitter.split(p1);

                        if (DOC_ID == null) {
                            DOC_ID = docId;
                        } else {
                            if (!DOC_ID.equals(docId))
                                throw new RuntimeException("docIds are not equal to each other!");
                        }

                        if (Q_ID == null) {
                            Q_ID = parts[0] + " " + parts[1] + " " + parts[2] + " " + parts[3];
                            builder.append(Q_ID).append(" ");
                            f = 3;
                        } else {
                            if (!Q_ID.equals(parts[0] + " " + parts[1] + " " + parts[2] + " " + parts[3]))
                                throw new RuntimeException("docIds are not equal to each other!");
                        }

                        String[] copy = copy(parts, 4);

                        for (String s : copy) {
                            builder.append(Integer.toString(f++));
                            builder.append(":");
                            builder.append(s);
                            builder.append(" ");
                        }
                    }
                    out.print(builder.toString());
                    out.print("# ");
                    out.print(DOC_ID);
                    out.println();
                }
                out.flush();

            } catch (IOException ioe) {
                ioe.printStackTrace();
            }

        }

    }

    @Override
    public void run(Properties props) throws Exception {

        if (parseArguments(props) == -1) return;

        final String tfd_home = props.getProperty("tfd.home");

        if (tfd_home == null) {
            System.out.println(getHelp());
            return;
        }

        DataSet dataSet = CollectionFactory.dataset(collection, tfd_home);

        if ("spam".equals(task)) {

            List<String> models = Arrays.asList("DPH.features", "DFIC.features", "DFRee.features", "PL2.features", "DLH13.features", "BM25.features", "LGD.features", "Dirichlet.features");


            List<List<String>> dupDOCNOlist = new ArrayList<>();
            for (String line : Files.readAllLines(Paths.get(tfd_home, "topics-and-qrels", "dupDOCNOlist.txt"))) {
                dupDOCNOlist.add(Arrays.asList(whiteSpaceSplitter.split(line)));
            }

            Path path = Paths.get(tfd_home, collection.toString(), "features");

            try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(path)) {
                for (Path in : directoryStream) {
                    if (Files.isDirectory(in)) continue;
                    if (models.contains(in.getFileName().toString())) {
                        System.out.println("Processing " + in.toString());
                        addPageRank(in, path.resolve("X." + in.getFileName().toString()), dupDOCNOlist);
                    }
                }
            }
            return;
        }

        if ("merge".equals(task)) {

            Path path = Paths.get(tfd_home, collection.toString(), "features");

            merge(path);

            return;
        }


        List<InfoNeed> needs = dataSet.getTopics();

        Set<String> models = new TreeSet<>();

        for (String parametricModel : parametricModels)
            models.add(ParamTool.train(parametricModel, dataSet, tag, measure, "OR").toString());

        System.out.println("========= best parameters ===========");
        System.out.println(models);

        models.add("DFIC");
        models.add("DPH");
        models.add("DFRee");
        models.add("DLH13");


        String runsDirectory = spam == 0 ? "runs" : "spam_" + spam + "_runs";


        Path samplePath = dataSet.collectionPath().resolve("samples");
        if (!Files.exists(samplePath))
            Files.createDirectory(samplePath);


        for (Track track : dataSet.tracks()) {

            Path thePath = dataSet.collectionPath().resolve(runsDirectory).resolve(tag).resolve(track.toString());

            if (!Files.exists(thePath) || !Files.isDirectory(thePath) || !Files.isReadable(thePath))
                throw new IllegalArgumentException(thePath + " does not exist or is not a directory.");

            List<Path> paths = discoverTextFiles(thePath, "_OR_all.txt");

            for (String model : models) {

                Path outPath = samplePath.resolve(track.toString() + "." + Evaluator.prettyModel(model) + ".txt");
                PrintWriter out = new PrintWriter(Files.newBufferedWriter(outPath, StandardCharsets.US_ASCII));

                int c = 0;
                for (Path path : paths) {
                    if (path.getFileName().toString().startsWith(model + "_")) {
                        c++;
                        List<String> lines = Files.readAllLines(path, StandardCharsets.US_ASCII);

                        for (String s : lines) {

                            String[] parts = whiteSpaceSplitter.split(s);

                            if (parts.length != 6)
                                throw new RuntimeException("submission file does not contain 6 columns " + s);

                            final String docId = parts[2];

                            final int qID = Integer.parseInt(parts[0]);

                            InfoNeed need = new InfoNeed(qID, "", track, Collections.emptyMap());

                            int i = needs.indexOf(need);
                            if (-1 == i) {
                                System.out.println("cannot find information need " + qID);
                                continue;
                            }
                            final int judge = needs.get(i).getJudgeMap().getOrDefault(docId, 0);
                            out.println(qID + " 0 " + docId + " " + Integer.toString(judge == -2 ? 0 : judge));

                        }
                    }
                }
                if (c != 1) throw new RuntimeException(c + " many files start with for model " + model);

                out.flush();
                out.close();
            }
        }


        if (dataSet.tracks().length == 1) return;
        for (String model : models) {

            Path unifiedPath = samplePath.resolve(dataSet.collection().toString() + "." + Evaluator.prettyModel(model) + ".txt");

            final PrintWriter out = new PrintWriter(Files.newBufferedWriter(unifiedPath, StandardCharsets.US_ASCII));

            for (Track track : dataSet.tracks()) {

                Path localPath = samplePath.resolve(track.toString() + "." + Evaluator.prettyModel(model) + ".txt");

                List<String> lines = Files.readAllLines(localPath, StandardCharsets.US_ASCII);

                for (String line : lines)
                    out.println(line);

                out.flush();
                lines.clear();

            }
            out.flush();
            out.close();
        }
    }

    private void addPageRank(Path in, Path out, List<List<String>> dupDOCNOlist) throws IOException, SolrServerException {

        final HttpSolrClient rankSolr = new HttpSolrClient.Builder().withBaseSolrUrl("http://irra-micro.nas.ceng.local:8983/solr/rank09A").build();
        final HttpSolrClient spamSolr = new HttpSolrClient.Builder().withBaseSolrUrl("http://irra-micro.nas.ceng.local:8983/solr/spam09A").build();


        int featureNum = -1;
        boolean first = true;
        try (BufferedReader reader = Files.newBufferedReader(in, StandardCharsets.US_ASCII);
             PrintWriter writer = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.US_ASCII))) {

            for (; ; ) {
                String line = reader.readLine();
                if (line == null)
                    break;

                //#18:SimplifiedClarityScore
                //#19:PMIFeature
                //#20:AvgSCQ
                if (line.startsWith("#")) {
                    try {
                        featureNum = Integer.parseInt(line.substring(1, line.indexOf(":")));
                    } catch (NumberFormatException nfe) {
                        //NO-OP
                    }
                    writer.println(line);
                    continue;
                } else {

                    if (first) {

                        if (-1 == featureNum) {

                            int i = line.indexOf("#");

                            if (i != -1) {
                                String p1 = line.substring(0, i).trim();
                                String[] parts = whiteSpaceSplitter.split(p1);
                                String last = parts[parts.length - 1];

                                i = last.indexOf(":");

                                if (i != -1) {
                                    featureNum = Integer.parseInt(last.substring(0, i).trim());
                                }
                            }

                        }

                        //#21:SpamScore
                        writer.println("#" + (featureNum + 1) + ":SpamScore");
                        //#22:PageRank
                        writer.println("#" + (featureNum + 2) + ":PageRank");
                        first = false;
                    }
                }

                // 0 qid:1 1:81.0 2:86.0 3:122.0 4:122.0 5:50.0 6:86.0 7:86.0 8:3.0 9:3.0 10:81.0 11:81.0 12:18.444643 13:247.42574 14:3.0 15:827.0 16:3.4250813 17:2.9271529 18:7.4784217 19:-2.817856 20:17.85046 # clueweb09-en0010-79-02218
                int i = line.indexOf("#");

                if (i == -1) {
                    throw new RuntimeException("cannot find # in " + line);
                }

                String p1 = line.substring(0, i).trim();
                String docId = line.substring(i + 1).trim();

                int spam = SpamTool.percentile(spamSolr, docId);
                double rank = pageRank(rankSolr, docId, dupDOCNOlist);

                writer.println(p1 + " " + Integer.toString(featureNum + 1) + ":" + spam + " " + Integer.toString(featureNum + 2) + ":" + rank + " # " + docId);
                writer.flush();

            }
        }

        spamSolr.close();
        rankSolr.close();
    }

    /**
     * Retrieve page rank of a given document id e.g., clueweb09-enwp01-38-03709
     * <p>
     * The lists contain 502,511,675 DOCNOs after deduplication. All duplicate URLs are removed from this list, during translating node_ids to WARC DOCNOs.
     * For DOCNOs with the same URL, only the smallest DOCNO is kept as the DOCNO for all the node_ids corresponding to that URL.
     */
    private static double pageRank(HttpSolrClient solr, String docID, List<List<String>> dupDOCNOlist) throws IOException, SolrServerException {

        SolrQuery query = new SolrQuery(docID).setFields("rank");
        query.set(HEADER_ECHO_PARAMS, CommonParams.EchoParamStyle.NONE.toString());
        query.set(OMIT_HEADER, true);
        SolrDocumentList resp = solr.query(query).getResults();


        /**
         * Look up in the duplicate record list
         */
        if (resp.size() == 0) {

            for (List<String> list : dupDOCNOlist) {
                if (list.contains(docID)) {
                    for (String id : list) {

                        if (docID.equals(id)) continue;

                        double pRank = pageRank(solr, id);
                        if (pRank != -1.0) {
                            System.out.println("Not found in the pagerank data, but found in the duplicate record list: " + docID + " " + pRank);
                            return pRank;
                        }
                    }
                }
            }

            System.out.println("There are a small number of DOCNOs that are not in the pagerank data and are not included in the duplicate record list: " + docID);
            return 0.1914; // Average PageRank value : 96176607.1109954 / 502511675 from http://www.lemurproject.org/clueweb09/pageRank.php


        }

        if (resp.size() != 1) {
            System.out.println("docID " + docID + " returned " + resp.size() + " many hits!");
        }

        double rank = (double) resp.get(0).getFieldValue("rank");

        resp.clear();
        query.clear();

        return rank;
    }

    private static double pageRank(HttpSolrClient solr, String docID) throws IOException, SolrServerException {

        SolrQuery query = new SolrQuery(docID).setFields("rank");
        query.set(HEADER_ECHO_PARAMS, CommonParams.EchoParamStyle.NONE.toString());
        query.set(OMIT_HEADER, true);
        SolrDocumentList resp = solr.query(query).getResults();


        if (resp.size() == 0) {
            return -1.0;
        }

        if (resp.size() != 1) {
            System.out.println("docID " + docID + " returned " + resp.size() + " many hits!");
        }

        double rank = (double) resp.get(0).getFieldValue("rank");

        resp.clear();
        query.clear();

        return rank;
    }

    public static void main(String[] args) {

        String line = "0 qid:1 1:81.0 2:86.0 3:122.0 4:122.0 5:50.0 6:86.0 7:86.0 8:3.0 9:3.0 10:81.0 11:81.0 12:18.444643 13:247.42574 14:3.0 15:827.0 16:3.4250813 17:2.9271529 18:7.4784217 19:-2.817856 20:17.85046 # clueweb09-en0010-79-02218";

        int i = line.indexOf("#");

        if (i == -1) {
            System.out.println(line);

        }

        String p1 = line.substring(0, i).trim();
        String docId = line.substring(i + 1).trim();
        System.out.println(p1);
        System.out.println(docId);

        int spam = 50;
        double rank = 455.564546;
        System.out.println(p1 + " 21:" + spam + " 22:" + rank + " # " + docId);

        String[] src = whiteSpaceSplitter.split("0 qid:1 1:81.0 2:86.0 3:122.0 4:122.0 5:50.0 6:86.0 7:86.0 8:3.0 9:3.0 10:81.0 11:81.0 12:18.444643 13:247.42574 14:3.0 15:827.0 16:3.4250813 17:2.9271529 18:7.4784217 19:-2.817856 20:17.85046");

        System.out.println(Arrays.toString(copy(src, 4)));
    }
}
