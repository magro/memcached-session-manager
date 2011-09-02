package de.javakaffee.web.msm;

import net.spy.memcached.MemcachedClient;
import net.spy.memcached.vbucket.ConfigurationException;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.InetSocketAddress;

import java.util.ArrayList;
import java.util.List;
import java.io.IOException;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
 *
 * 
 */
public class MembaseSessionService extends MemcachedSessionService {
   private SessionManager _manager;
   protected final Log _log = LogFactory.getLog( getClass() );

   public MembaseSessionService() {
       super(null);
   }

   public MembaseSessionService( final SessionManager manager ) {
        _manager = manager;
   }

   protected MemcachedClient createMemcachedClient(final MemcachedNodesManager memcachedNodesManager,
            final Statistics statistics ) {
            MemcachedClient mc;
            List<InetSocketAddress> addresses = memcachedNodesManager.getAllMemcachedAddresses();
            ArrayList baseURIs = new ArrayList();
           try {
               for(InetSocketAddress address : addresses) {
                   String uri = address.getAddress().toString();
                   URI baseUri = new URI(uri);
                   baseURIs.add(baseUri);
               }
               String username = getUsername();
               String password = getPassword();

               return new MemcachedClient(baseURIs, username, password);
                   /* this could also be MemcachedClient(serverList, "default", "") in the case
                    * you're using a default bucket
                    */
               } catch (IOException ex) {
                 _log.error( "IO Exception creating connection: ");
               } catch (ConfigurationException ex) {
                 _log.error( "ConfigurationException: ");
               } catch (URISyntaxException ex) {
                 _log.error( "URISyntaxException : ");
               }
      return null;
    }


}
