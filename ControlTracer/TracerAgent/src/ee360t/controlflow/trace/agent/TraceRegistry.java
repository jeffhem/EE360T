package ee360t.controlflow.trace.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ee360t.controlflow.utility.ControlFlow;

import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class TraceRegistry {
    private static Map<String, String> sourceFileNames = new HashMap<>();

    private static Map<MethodId, Integer> globalMethodIds = new HashMap<>();
    private static Map<Integer, MethodId> methodIds = new HashMap<>();
    private static Map<Integer, ControlFlow> controlFlows = new HashMap<>();

    private static Map<NodeId, Integer> globalNodeIds = new HashMap<>();
    private static Map<Integer, NodeId> nodeIds = new HashMap<>();

    private static List<TraceRecord> traceRecords = new ArrayList<>();
    private static TraceRecord currentRecord;

    public static void startNewTrace( String testClassName, String testMethodName, String testMethodDescriptor ) {
        System.out.println( "New trace started for " + testClassName + "." + testMethodName + testMethodDescriptor );
        currentRecord = new TraceRecord( testClassName, testMethodName, testMethodDescriptor );
        traceRecords.add( currentRecord );
    }

    public static void visitNode( int globalNodeId ) {
        if( currentRecord == null )
            throw new RuntimeException( "New trace record must be started before visiting any nodes." );
        System.out.println( "Visited node with global ID: " + globalNodeId );
        currentRecord.addNode( globalNodeId );
    }

    public static int getGlobalNodeId( String className, String methodName, String methodDescriptor, int localNodeId ) {
        NodeId nodeId = new NodeId( className, methodName, methodDescriptor, localNodeId );
        return globalNodeIds.computeIfAbsent( nodeId, key -> {
            int globalNodeId = globalNodeIds.size();
            nodeIds.put( globalNodeId, key );
            return globalNodeId;
        } );
    }

    public static int getGlobalMethodId( String className, String methodName, String methodDescriptor ) {
        MethodId methodId = new MethodId( className, methodName, methodDescriptor );
        return globalMethodIds.computeIfAbsent( methodId, key -> {
            int globalMethodId = globalMethodIds.size();
            methodIds.put( globalMethodId, key );
            return globalMethodId;
        } );
    }

    public static void setControlFlow( ControlFlow controlFlow, String className, String methodName,
                                       String methodDescriptor ) {
        int globalMethodId = getGlobalMethodId( className, methodName, methodDescriptor );
        controlFlows.put( globalMethodId, controlFlow );
    }

    public static void setSourceFileName( String className, String sourceFileName ) {
        sourceFileNames.put( className, sourceFileName );
    }

    public static void serialize( String outputPath, List<String> sourcePaths ) {
        Gson gson = new GsonBuilder()
                .serializeNulls()
                .registerTypeAdapter( TraceRecord.class, new TraceRecordSerializer() )
                .registerTypeAdapter( NodeId.class, new NodeIdSerializer() )
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create();

        JsonObject results = new JsonObject();
        results.add( "traceRecords", gson.toJsonTree( traceRecords ) );

        JsonObject controlFlowsJson = new JsonObject();
        Map<String, JsonObject> controlFlowsForClass = new HashMap<>();
        Map<String, List<String>> sourceLinesForClass = new HashMap<>();

        for( int globalMethodId : controlFlows.keySet() ) {
            MethodId methodId = methodIds.get( globalMethodId );
            ControlFlow controlFlow = controlFlows.get( globalMethodId );

            String className = methodId.getClassName();
            JsonObject controlFlowJson = controlFlowsForClass.computeIfAbsent( className,
                trash -> {
                    // Setup the new object for this class.
                    JsonObject json = new JsonObject();
                    String[] classNameParts = methodId.getClassName().split( "/" );
                    String displayName = classNameParts[classNameParts.length - 1];

                    controlFlowsJson.add( className, json );
                    json.addProperty( "classDisplayName", displayName );
                    json.add( "methods", new JsonArray() );

                    // Read the source lines for this class in case we need to annotate our method nodes. We assume that
                    // the source file for a class is stored in its package on disk, with each package rooted in one of
                    // the given source paths. Since our class name parts is prefixed by the class's package, we just
                    // replace the class name with the class source file to form the relative path to the class's source
                    // file.
                    String sourceFileName = sourceFileNames.get( className );
                    if( sourceFileName != null ) {
                        classNameParts[classNameParts.length - 1] = sourceFileName;
                        sourceLinesForClass.put( className, getSourceLines( classNameParts, sourcePaths ) );
                    }
                    return json;
                } );

            JsonArray methodsJson = controlFlowJson.getAsJsonArray( "methods" );
            JsonObject methodJson = new JsonObject();
            methodJson.addProperty( "methodName", methodId.getMethodName() );
            methodJson.addProperty( "methodDescriptor", methodId.getMethodDescriptor() );
            methodJson.addProperty(  "globalMethodId", globalMethodId );

            // Serialize the annotated global IDs for this method's nodes.
            List<String> sourceLines = sourceLinesForClass.get( className );
            Map<Integer, Integer> localToGlobalNodeId = new HashMap<>();
            Map<Integer, String> globalNodes = new HashMap<>();
            for( int iNode : controlFlow.getNodes() ) {
                int globalNodeId = globalNodeIds.get( new NodeId( className,
                        methodId.getMethodName(),
                        methodId.getMethodDescriptor(),
                        iNode ) );

                localToGlobalNodeId.put( iNode, globalNodeId );

                // Attempt to get the annotation for this node. For ENTRY and EXIT nodes, we have special annotations.
                // For all other nodes, we attempt to get the corresponding source line, if there is one.
                String annotation = null;
                if( iNode == ControlFlow.ENTRY ) {
                    annotation = "[ENTRY]";
                }
                else if( iNode == ControlFlow.EXIT ) {
                    annotation = "[EXIT]";
                }
                else if( sourceLines != null ) {
                    int sourceLineNumber = controlFlow.getSourceLineNumber( iNode );
                    if( sourceLineNumber != ControlFlow.INVALID_LINE ) {
                        // Line numbers are 1-based.
                        annotation = String.format( "Line %d: %s", sourceLineNumber,
                                sourceLines.get( sourceLineNumber - 1 ).trim() );
                    }
                }

                globalNodes.put( globalNodeId, annotation );
            }
            methodJson.add( "nodes", gson.toJsonTree( globalNodes ) );

            // Serialize the edges for this method, with local node IDs replaced with their global IDs.
            Map<Integer, Set<Integer>> edges = controlFlow.getEdges();
            Map<Integer, Set<Integer>> globalEdges = new HashMap<>();
            for( int iFrom : edges.keySet() ) {
                Set<Integer> globalSuccessors = new HashSet<>();

                for( int iTo : edges.get( iFrom ) ) {
                    globalSuccessors.add( localToGlobalNodeId.get( iTo ) );
                }

                globalEdges.put( localToGlobalNodeId.get( iFrom ), globalSuccessors );
            }
            methodJson.add( "edges", gson.toJsonTree( globalEdges ) );
            methodsJson.add( methodJson );
        }

        results.add( "controlFlows", controlFlowsJson );
        results.add( "globalToLocalNodeId", gson.toJsonTree( nodeIds ) );

        try( Writer outputWriter = new FileWriter( outputPath ) ) {
            outputWriter.write( gson.toJson( results ) );
        }
        catch( Exception ex ) {
            System.err.println( "Error writing trace results." );
            ex.printStackTrace( System.err );
        }
    }

    private static List<String> getSourceLines( String[] sourceFilePathParts, List<String> sourcePaths ) {
        for( String sourcePath : sourcePaths ) {
            Path sourceFilePath = Paths.get( sourcePath, sourceFilePathParts );
            if( Files.exists( sourceFilePath ) ) {
                try {
                    return Files.readAllLines( sourceFilePath );
                }
                catch( IOException ex ) {
                    System.err.println( "Failed to read source file path " + sourceFilePath.toString() );
                    return null;
                }
            }
        }
        return null;
    }

    private TraceRegistry() {}
}
