package org.keycloak.protocol.saml;

import org.jboss.logging.Logger;
import org.jboss.resteasy.client.ClientRequest;
import org.jboss.resteasy.client.ClientResponse;
import org.jboss.resteasy.client.core.executors.ApacheHttpClient4Executor;
import org.keycloak.models.ApplicationModel;
import org.keycloak.models.ClaimMask;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientSessionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.LoginProtocol;
import org.keycloak.services.managers.ClientSessionCode;
import org.keycloak.services.managers.ResourceAdminManager;
import org.keycloak.services.resources.RealmsResource;
import org.keycloak.services.resources.flows.Flows;
import org.keycloak.util.PemUtils;
import org.picketlink.common.constants.GeneralConstants;
import org.picketlink.common.constants.JBossSAMLURIConstants;
import org.picketlink.identity.federation.core.saml.v2.constants.X500SAMLProfileConstants;
import org.picketlink.identity.federation.web.handlers.saml2.SAML2LogOutHandler;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.security.PublicKey;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public class SalmProtocol implements LoginProtocol {
    protected static final Logger logger = Logger.getLogger(SalmProtocol.class);
    public static final String LOGIN_PROTOCOL = "saml";
    public static final String SAML_BINDING = "saml_binding";
    public static final String SAML_POST_BINDING = "post";

    protected KeycloakSession session;

    protected RealmModel realm;

    protected UriInfo uriInfo;



    @Override
    public SalmProtocol setSession(KeycloakSession session) {
        this.session = session;
        return this;
    }

    @Override
    public SalmProtocol setRealm(RealmModel realm) {
        this.realm = realm;
        return this;
    }

    @Override
    public SalmProtocol setUriInfo(UriInfo uriInfo) {
        this.uriInfo = uriInfo;
        return this;
    }

    @Override
    public Response cancelLogin(ClientSessionModel clientSession) {
        return getErrorResponse(clientSession, JBossSAMLURIConstants.STATUS_REQUEST_DENIED.get());
    }

    @Override
    public Response invalidSessionError(ClientSessionModel clientSession) {
        return getErrorResponse(clientSession, JBossSAMLURIConstants.STATUS_AUTHNFAILED.get());
    }

    protected String getResponseIssuer(RealmModel realm) {
        return RealmsResource.realmBaseUrl(uriInfo).build(realm.getName()).toString();
    }

    protected Response getErrorResponse(ClientSessionModel clientSession, String status) {
        SAML2PostBindingErrorResponseBuilder builder = new SAML2PostBindingErrorResponseBuilder()
                .relayState(clientSession.getNote(GeneralConstants.RELAY_STATE))
                .destination(clientSession.getRedirectUri())
                .responseIssuer(getResponseIssuer(realm));
      try {
            return builder.buildErrorResponse(status);
        } catch (Exception e) {
            return Flows.forwardToSecurityFailurePage(session, realm, uriInfo, "Failed to process response");
        }
    }

    @Override
    public Response authenticated(UserSessionModel userSession, ClientSessionCode accessCode) {
        ClientSessionModel clientSession = accessCode.getClientSession();
        if (SalmProtocol.SAML_POST_BINDING.equals(clientSession.getNote(SalmProtocol.SAML_BINDING))) {
            return postBinding(userSession, clientSession);
        }
        throw new RuntimeException("still need to implement redirect binding");
    }

    protected Response postBinding(UserSessionModel userSession, ClientSessionModel clientSession) {
        String requestID = clientSession.getNote("REQUEST_ID");
        String relayState = clientSession.getNote(GeneralConstants.RELAY_STATE);
        String redirectUri = clientSession.getRedirectUri();
        String responseIssuer = getResponseIssuer(realm);

        SALM2PostBindingLoginResponseBuilder builder = new SALM2PostBindingLoginResponseBuilder();
        builder.requestID(requestID)
               .relayState(relayState)
               .destination(redirectUri)
               .responseIssuer(responseIssuer)
               .requestIssuer(clientSession.getClient().getClientId())
               .userPrincipal(userSession.getUser().getUsername()) // todo userId instead?  There is no username claim it seems
               .attribute(X500SAMLProfileConstants.USERID.getFriendlyName(), userSession.getUser().getId())
               .authMethod(JBossSAMLURIConstants.AC_UNSPECIFIED.get());
        initClaims(builder, clientSession.getClient(), userSession.getUser());
        if (clientSession.getRoles() != null) {
            for (String roleId : clientSession.getRoles()) {
                // todo need a role mapping
                RoleModel roleModel = clientSession.getRealm().getRoleById(roleId);
                builder.roles(roleModel.getName());
            }
        }
        ClientModel client = clientSession.getClient();
        if (requiresRealmSignature(client)) {
            builder.sign(realm.getPrivateKey(), realm.getPublicKey());
        }
        if (requiresEncryption(client)) {
            PublicKey publicKey = null;
            try {
                publicKey = PemUtils.decodePublicKey(client.getAttribute(ClientModel.PUBLIC_KEY));
            } catch (Exception e) {
                logger.error("failed", e);
                return Flows.forwardToSecurityFailurePage(session, realm, uriInfo, "Failed to process response");
            }
            builder.encrypt(publicKey);
        }
        try {
            return builder.buildLoginResponse();
        } catch (Exception e) {
            logger.error("failed", e);
            return Flows.forwardToSecurityFailurePage(session, realm, uriInfo, "Failed to process response");
        }
    }

    private boolean requiresRealmSignature(ClientModel client) {
        return "true".equals(client.getAttribute("samlServerSignature"));
    }

    private boolean requiresEncryption(ClientModel client) {
        return "true".equals(client.getAttribute("samlEncrypt"));
    }

    public void initClaims(SALM2PostBindingLoginResponseBuilder builder, ClientModel model, UserModel user) {
        if (ClaimMask.hasEmail(model.getAllowedClaimsMask())) {
            builder.attribute(X500SAMLProfileConstants.EMAIL_ADDRESS.getFriendlyName(), user.getEmail());
        }
        if (ClaimMask.hasName(model.getAllowedClaimsMask())) {
            builder.attribute(X500SAMLProfileConstants.GIVEN_NAME.getFriendlyName(), user.getFirstName());
            builder.attribute(X500SAMLProfileConstants.SURNAME.getFriendlyName(), user.getLastName());
        }
    }


    @Override
    public Response consentDenied(ClientSessionModel clientSession) {
        return getErrorResponse(clientSession, JBossSAMLURIConstants.STATUS_REQUEST_DENIED.get());
    }

    @Override
    public void backchannelLogout(UserSessionModel userSession, ClientSessionModel clientSession) {
        ClientModel client = clientSession.getClient();
        if (!(client instanceof ApplicationModel)) return;
        ApplicationModel app = (ApplicationModel)client;
        if (app.getManagementUrl() == null) return;

        SAML2PostBindingLogoutResponseBuilder logoutBuilder = new SAML2PostBindingLogoutResponseBuilder()
                                         .userPrincipal(userSession.getUser().getUsername())
                                         .destination(client.getClientId());
        if (requiresRealmSignature(client)) {
            logoutBuilder.sign(realm.getPrivateKey(), realm.getPublicKey());
        }
        /*
        if (requiresEncryption(client)) {
            PublicKey publicKey = null;
            try {
                publicKey = PemUtils.decodePublicKey(client.getAttribute(ClientModel.PUBLIC_KEY));
            } catch (Exception e) {
                logger.error("failed", e);
                return;
            }
            logoutBuilder.encrypt(publicKey);
        }
        */

        String logoutRequestString = null;
        try {
            logoutRequestString = logoutBuilder.buildRequestString();
        } catch (Exception e) {
            logger.warn("failed to send saml logout", e);
            return;
        }


        String adminUrl = ResourceAdminManager.getManagementUrl(uriInfo.getRequestUri(), app);

        ApacheHttpClient4Executor executor = ResourceAdminManager.createExecutor();


        try {
            ClientRequest request = executor.createRequest(adminUrl);
            request.formParameter(GeneralConstants.SAML_REQUEST_KEY, logoutRequestString);
            request.formParameter(SAML2LogOutHandler.BACK_CHANNEL_LOGOUT, SAML2LogOutHandler.BACK_CHANNEL_LOGOUT);
            ClientResponse response = null;
            try {
                response = request.post();
                response.releaseConnection();
                // Undertow will redirect root urls not ending in "/" to root url + "/".  Test for this weird behavior
                if (response.getStatus() == 302  && !adminUrl.endsWith("/")) {
                    String redirect = (String)response.getHeaders().getFirst(HttpHeaders.LOCATION);
                    String withSlash = adminUrl + "/";
                    if (withSlash.equals(redirect)) {
                        request = executor.createRequest(withSlash);
                        request.formParameter(GeneralConstants.SAML_REQUEST_KEY, logoutRequestString);
                        request.formParameter(SAML2LogOutHandler.BACK_CHANNEL_LOGOUT, SAML2LogOutHandler.BACK_CHANNEL_LOGOUT);
                        response = request.post();
                        response.releaseConnection();
                    }
                }
            } catch (Exception e) {
                logger.warn("failed to send saml logout", e);
            }

        } finally {
            executor.getHttpClient().getConnectionManager().shutdown();
        }

    }

    @Override
    public void close() {

    }
}
