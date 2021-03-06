package org.openimaj.webservice.twitter;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;

import javax.jws.WebService;

import org.apache.log4j.Logger;
import org.kohsuke.args4j.CmdLineException;
import org.openimaj.util.pair.IndependentPair;
import org.restlet.Application;
import org.restlet.Restlet;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.representation.OutputRepresentation;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.Post;
import org.restlet.resource.ResourceException;
import org.restlet.routing.Router;

/**
 * 
 * @author Sina Samangooei (ss@ecs.soton.ac.uk)
 */
@WebService(targetNamespace = "http")
public class PreProcessApp extends Application {

	private static Logger logger = Logger.getLogger(PreProcessApp.class);

	
	/**
	 * @param e
	 * @return
	 */
	public static Representation errorRep(CmdLineException e) {
		StringWriter writer = new StringWriter();
		PrintWriter pw = new PrintWriter(writer);
		e.printStackTrace(pw);
		e.getParser().printUsage(pw,null);
		pw.flush();
		StringRepresentation stringRepresentation = new StringRepresentation(writer.toString());
		return stringRepresentation;
	}
	
	/**
	 * @return a representation and outputstreamwriter which will reply to the user after this method returns
	 */
	public static IndependentPair<? extends Representation, OutputStreamWriter> prepareOutputPipe() {
		logger.debug("Preparing output pipes");
		final PipedInputStream pi = new PipedInputStream();
		PipedOutputStream po = null;
		try {
			po = new PipedOutputStream(pi);
		} catch (IOException e) {
			logger.error("Failed to create output pipe",e);
			return IndependentPair.pair(new StringRepresentation("Could not open output pipe"),null);
		}
		Representation ir = new OutputRepresentation(MediaType.APPLICATION_JSON) {
			@Override
			public void write(OutputStream realOutput) throws IOException {
				byte[] b = new byte[8];
				int read;
				while ((read = pi.read(b)) != -1) {
					realOutput.write(b, 0, read);
					realOutput.flush();
				}
			}
		};
		OutputStreamWriter ow = null;
		try {
			ow = new OutputStreamWriter(po,"UTF-8");
		} catch (UnsupportedEncodingException e) {
			try {
				ow.close();
			} catch (IOException e1) {
				logger.error("Failed to create output pipe",e1);
				return IndependentPair.pair(new StringRepresentation("Could not open output pipe"),null);
			}
		}
		
		return IndependentPair.pair(ir,ow);
	}
	/**
	 * 
	 * @author Sina Samangooei (ss@ecs.soton.ac.uk)
	 */
	public static class PreProcessService extends AppTypedResource<PreProcessApp> {
		
		/**
		 * @param entity
		 * @return rep
		 */
		@Post
		public Representation represent(Representation entity) {
			logger.debug("Starting request");
			logger.debug("Parsing options");
			PreProcessAppOptions options;
			try {
				
				options = new PreProcessAppOptions(getQuery(),getRequestAttributes());
			} catch (CmdLineException e) {
				logger.error("Invalid options",e);
				entity.release();
				this.setStatus(Status.SERVER_ERROR_INTERNAL);
				return errorRep(e);
			}
			IndependentPair<? extends Representation, OutputStreamWriter> irow = prepareOutputPipe();
			Representation ir = irow.firstObject();
			OutputStreamWriter ow = irow.secondObject();
			if(ow == null) {
				this.setStatus(Status.SERVER_ERROR_INTERNAL);
				return ir;
			}
			options.setOutputWriter(ow);
			
			logger.debug("Preparing input data");
			if (entity != null) {
				
				if (MediaType.MULTIPART_FORM_DATA.equals(entity.getMediaType(),true)) {
					try {
						logger.debug("Starting input thread");
						new Thread(new PreProcessTask(getRequest(), options)).start();
//						new Thread(new PreProcessFileUploadTask(getRequest(), options)).start();
					} catch (IOException e) {
						logger.error("No input data found");
						this.setStatus(Status.SERVER_ERROR_INTERNAL);
						return new StringRepresentation("No valid file provided, use variable 'data'");
					}
				} else {
					logger.error("Not a multipart request");
					this.setStatus(Status.SERVER_ERROR_INTERNAL);
					return new StringRepresentation("Not a multipart request, upload a file using variable 'data'");
				}
			} else {
				this.setStatus(Status.SERVER_ERROR_INTERNAL);
				return new StringRepresentation("No valid file provided, use variable 'data'");
			}
			logger.debug("Success! starting output");
			this.setStatus(Status.SUCCESS_OK);
			return ir;
		}

		

		

		
	}

	@Override
	public Restlet createInboundRoot() {
		Router router = new Router(getContext());
		router.attach("/{intype}.{outtype}", PreProcessService.class);
		return router;
	}
}
