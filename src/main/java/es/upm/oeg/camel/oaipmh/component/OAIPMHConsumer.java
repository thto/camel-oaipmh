package es.upm.oeg.camel.oaipmh.component;

import es.upm.oeg.camel.oaipmh.dataformat.OAIPMHConverter;
import es.upm.oeg.camel.oaipmh.handler.ResponseHandler;
import es.upm.oeg.camel.oaipmh.handler.ResponseHandlerFactory;
import es.upm.oeg.camel.oaipmh.model.*;
import org.apache.camel.Processor;
import org.apache.camel.impl.DefaultScheduledPollConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.bind.JAXBException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

/**
 * The OAIPMH consumer.
 */
public class OAIPMHConsumer extends DefaultScheduledPollConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(OAIPMHConsumer.class);

    private static final ResumptionTokenType NO_TOKEN = null;

    private final OAIPMHEndpoint endpoint;
    private final OAIPMHHttpClient httpClient;
    private final ObjectFactory factory;
    private final URI baseURI;
    private final String verb;
    private final String metadata;
    private final ResponseHandler handler;
    private String until;
    private String from;
    private String set;

    public OAIPMHConsumer(OAIPMHEndpoint endpoint, Processor processor) throws JAXBException {
        super(endpoint, processor);
        this.endpoint   = endpoint;
        this.httpClient = new OAIPMHHttpClient();
        this.factory    = new ObjectFactory();
        this.from       = endpoint.getFrom();
        this.baseURI    = URI.create("http://" + endpoint.getUrl());
        this.verb       = endpoint.getVerb();
        this.set        = endpoint.getSet();
        this.metadata   = endpoint.getMetadataPrefix();
        this.until      = endpoint.getUntil();
        this.handler    = ResponseHandlerFactory.newInstance(this,verb);
    }

    @Override
    protected int poll() throws Exception {
        return poll(NO_TOKEN);
    }

    protected int poll(ResumptionTokenType token) throws IOException, URISyntaxException, JAXBException {

        // if 'from' more actual than 'until' and not 'resumptionToken', then exit (it means that polling has been already done)

        // selective harvesting
        if (this.until != null && !this.until.trim().equals("")) {
            // only when 'from' is older than 'until' then it is valid
            if (token == null && (TimeUtils.fromISO(this.from)>TimeUtils.fromISO(this.until))){
                LOG.info("System goes down because time window has been requested.");
                System.exit(0);
            }
        }

        // request 'verb' to remote data provider
        String responseXML = httpClient.doRequest(baseURI,verb,set,from,until,metadata,token);

        // Update reference time to current instant
        this.from = TimeUtils.current();


        // build a java object from xml
        OAIPMHtype responseObject = OAIPMHConverter.xmlToOaipmh(responseXML);

        // Check if error
        //TODO Retries Policy
        if (handleErrors(responseObject)){
            return 0;
        }

        // Split and send records to camel route (background mode)
        this.handler.process(responseObject);

        // Check if incomplete list
        ResumptionTokenType newToken = responseObject.getListRecords().getResumptionToken();

        boolean resume = (newToken != null) && (newToken.getValue() != null) && !(newToken.getValue().startsWith(" "));
        return (resume)? poll(newToken) : 1;
    }


    private boolean handleErrors(OAIPMHtype message){
        List<OAIPMHerrorType> errors = message.getError();
        if ((errors != null) && (!errors.isEmpty())){
            for (OAIPMHerrorType error: errors){

                switch(error.getCode()){
                    case NO_RECORDS_MATCH:
                    case NO_METADATA_FORMATS:
                    case NO_SET_HIERARCHY:
                        LOG.info("{} / {}",error.getCode(),error.getValue());
                        break;
                    default:
                        LOG.error("Error on [{}] getting records: {}-{}", endpoint.getUrl(),error.getCode(), error.getValue());
                }
            }
            return true;
        }
        return false;
    }

}
