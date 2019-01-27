package serviceimpl;

import biojobs.BioJob;
import biojobs.BioJobDao;
import biojobs.BioJobResultDao;
import enums.ParamPrefixes;
import exceptions.IncorrectRequestException;
import model.internal.ProtoTreeInternal;
import model.request.ProtoTreeRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import service.GeneNeighborhoodsService;
import service.StorageService;
import springconfiguration.AppProperties;

import java.util.*;

@Service
public class GeneNeighborhoodsServiceImpl extends BioUniverseServiceImpl implements GeneNeighborhoodsService {
    private Map<Integer, String> counterToStageOneInputPartial = new HashMap<>();
    private Map<Integer, String> counterToStageOneInputFull = new HashMap<>();
    private final String bootstrapFilePostfix = "_consensus";

    public GeneNeighborhoodsServiceImpl(final StorageService storageService, final AppProperties properties, final BioJobDao bioJobDao, final BioJobResultDao bioJobResultDao) {
        super(storageService, properties, bioJobResultDao, bioJobDao);
        counterToStageOneInputPartial.put(0, "['Processing input.-last']");
        counterToStageOneInputFull.put(0, "['Processing input.']");
        counterToStageOneInputFull.put(1, "['Processing input.', 'Aligning sequences and building phylogenetic tree.-last']");
    }

    @Override
    public ProtoTreeInternal storeFilesAndPrepareCommandArguments(ProtoTreeRequest protoTreeRequest) throws IncorrectRequestException {
        System.out.println("========================");
        System.out.println(protoTreeRequest.isFullPipeline());
        if (protoTreeRequest.isFullPipeline().equals("false"))
            return prepareNewick(protoTreeRequest);
        else
            return pipelineProcessing(protoTreeRequest);
    }

    @Override
    public BioJob getBioJob(int jobId) {
        return super.getBioJobDao().findByJobId(jobId);
    }

    private ProtoTreeInternal prepareNewick(ProtoTreeRequest protoTreeRequest) throws IncorrectRequestException {
        ProtoTreeInternal protoTreeInternal = storeFileAndGetInternalRepresentation(protoTreeRequest);
        List<String> listOfPrograms = new LinkedList<>();
        List<List<String>> listOfArgumentLists = new LinkedList<>();
        List<String> argsForPrepareNames = new LinkedList<>();

        String preparedFile = initArgsForPrepareNames(protoTreeInternal, argsForPrepareNames, listOfPrograms, listOfArgumentLists);
        protoTreeInternal.setOutputFilesNames(Arrays.asList(preparedFile));

        String[] arrayOfInterpreters = super.prepareInterpreters(listOfPrograms.size());
        String[] arrayOfPrograms = listOfPrograms.toArray(new String[listOfPrograms.size()]);
        super.prepareCommandArgumentsCommon(protoTreeInternal, arrayOfInterpreters, arrayOfPrograms, listOfArgumentLists);

        return protoTreeInternal;
    }

    private ProtoTreeInternal pipelineProcessing(ProtoTreeRequest protoTreeRequest) throws IncorrectRequestException {
        ProtoTreeInternal protoTreeInternal = storeFileAndGetInternalRepresentation(protoTreeRequest);
        List<String> listOfPrograms = new LinkedList<>();
        List<List<String>> listOfArgumentLists = new LinkedList<>();

        List<String> argsForPrepareNames = new LinkedList<>();
        List<String> argsForAlignmentAndTree = new LinkedList<>();
        List<String> argsForTreeEnumerate = new LinkedList<>();

        initArgsForPrepareNames(protoTreeInternal, argsForPrepareNames, listOfPrograms, listOfArgumentLists);

        protoTreeInternal.setFields();

        String numberOfThreadsForTree = "7";
        String numberOfThreadsForAlgn = "7";

        String outAlgnFile = super.getRandomFileName(".fa");
        String outNewickTree = super.getRandomFileName("noPostfix");
        argsForAlignmentAndTree.addAll(protoTreeInternal.getFieldsForAlignmentAndTreeBuild());
        argsForAlignmentAndTree.addAll(Arrays.asList(
                ParamPrefixes.MAFFT_PATH.getPrefix() + super.getProperties().getMafft(),
                ParamPrefixes.MEGACC_PATH.getPrefix() + super.getProperties().getMegacc(),
                ParamPrefixes.OUTPUT_PARAMS.getPrefix() + super.getPrefix() + UUID.randomUUID().toString() + super.getPostfix(),
                ParamPrefixes.OUTPUT_TREE.getPrefix() + outNewickTree,
                ParamPrefixes.THREAD_ALGN.getPrefix() + numberOfThreadsForAlgn,
                ParamPrefixes.THREAD.getPrefix() + numberOfThreadsForTree,
                ParamPrefixes.OUTPUT.getPrefix() + outAlgnFile
        ));

        if (!protoTreeRequest.getPhylogenyTest().equals("none")) {
            outNewickTree = outNewickTree + bootstrapFilePostfix;
        }

        String outNewickFile = super.getRandomFileName(".newick");
        String outOrderedAlgnFile = super.getRandomFileName(".fa");

        argsForTreeEnumerate.addAll(Arrays.asList(
                ParamPrefixes.INPUT_SECOND.getPrefix() + outAlgnFile,
                ParamPrefixes.INPUT_THIRD.getPrefix() + outNewickTree + ".nwk",
                ParamPrefixes.OUTPUT.getPrefix() + outOrderedAlgnFile,
                ParamPrefixes.OUTPUT_THIRD.getPrefix() + outNewickFile
        ));

        protoTreeInternal.setOutputFilesNames(Arrays.asList(outNewickFile, outOrderedAlgnFile));

        listOfPrograms.addAll(Arrays.asList(
                super.getProperties().getAlignAndBuildTree(),
                super.getProperties().getEnumerate()
        ));

        String[] arrayOfInterpreters = super.prepareInterpreters(listOfPrograms.size());
        String[] arrayOfPrograms = listOfPrograms.toArray(new String[listOfPrograms.size()]);

        listOfArgumentLists.addAll(Arrays.asList(
                argsForAlignmentAndTree,
                argsForTreeEnumerate
        ));

        super.prepareCommandArgumentsCommon(protoTreeInternal, arrayOfInterpreters, arrayOfPrograms, listOfArgumentLists);
        return protoTreeInternal;
    }

    private String initArgsForPrepareNames(ProtoTreeInternal protoTreeInternal, List<String> argsForPrepareNames,
                                           List<String> listOfPrograms, List<List<String>> listOfArgumentLists) {
        String preparedFile = null;
        if (!protoTreeInternal.isFullPipeline().equals("false")) {
            preparedFile = super.getRandomFileName(null);
            argsForPrepareNames.addAll(Arrays.asList(protoTreeInternal.getFirstFileName(), ParamPrefixes.OUTPUT.getPrefix() + preparedFile));
        } else if (protoTreeInternal.isFullPipeline().equals("false")) {
            preparedFile = super.getRandomFileName(".newick");
            argsForPrepareNames.addAll(Arrays.asList(protoTreeInternal.getTreeFile(), ParamPrefixes.OUTPUT_SECOND.getPrefix() + preparedFile));
        }
        protoTreeInternal.setFirstFileName(ParamPrefixes.INPUT.getPrefix() + preparedFile);
        listOfPrograms.add(super.getProperties().getPrepareNames());
        listOfArgumentLists.add(argsForPrepareNames);
        return preparedFile;
    }

    @Override
    @Async
    public void runMainProgram(ProtoTreeInternal protoTreeInternal) throws IncorrectRequestException {
        int counter = 0;
        for (List<String> commandArgument : protoTreeInternal.getCommandsAndArguments()) {
            if (protoTreeInternal.isFullPipeline().equals("false"))
                super.saveStage(protoTreeInternal, counter++, counterToStageOneInputPartial);
            else if (protoTreeInternal.isFullPipeline().equals("true"))
                super.saveStage(protoTreeInternal, counter++, counterToStageOneInputFull);
            super.launchProcess(commandArgument);
        }
        super.saveResultToDb(protoTreeInternal);
    }


}