/*
 * Copyright (C) 2014-2026 Philip Helger and contributors
 * philip[at]helger[dot]com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.helger.phoss.smp.rest;

import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.helger.annotation.Nonempty;
import com.helger.peppol.sml.ISMLInfo;
import com.helger.peppol.smlclient.ManageParticipantIdentifierServiceCaller;
import com.helger.peppol.smlclient.participant.BadRequestFault;
import com.helger.peppol.smlclient.participant.InternalErrorFault;
import com.helger.peppol.smlclient.participant.NotFoundFault;
import com.helger.peppol.smlclient.participant.UnauthorizedFault;
import com.helger.peppolid.IParticipantIdentifier;
import com.helger.peppolid.factory.IIdentifierFactory;
import com.helger.phoss.smp.config.SMPServerConfiguration;
import com.helger.phoss.smp.domain.SMPMetaManager;
import com.helger.phoss.smp.domain.pmigration.ISMPParticipantMigrationManager;
import com.helger.phoss.smp.domain.pmigration.SMPInboundMigrationHelper;
import com.helger.phoss.smp.domain.user.SMPUserManagerPhoton;
import com.helger.phoss.smp.exception.SMPBadRequestException;
import com.helger.phoss.smp.exception.SMPPreconditionFailedException;
import com.helger.phoss.smp.exception.SMPSMLException;
import com.helger.phoss.smp.exception.SMPServerException;
import com.helger.phoss.smp.restapi.ISMPServerAPIDataProvider;
import com.helger.phoss.smp.restapi.SMPAPICredentials;
import com.helger.phoss.smp.settings.ISMPSettings;
import com.helger.phoss.smp.smlhook.SmpSmlHelper;
import com.helger.photon.api.IAPIDescriptor;
import com.helger.photon.app.PhotonUnifiedResponse;
import com.helger.photon.security.user.IUser;
import com.helger.web.scope.IRequestWebScopeWithoutResponse;
import com.helger.xml.microdom.IMicroDocument;
import com.helger.xml.microdom.IMicroElement;
import com.helger.xml.microdom.MicroDocument;
import com.sun.xml.ws.client.ClientTransportException;

/**
 * REST API to perform an inbound migration for a participant
 *
 * @author Philip Helger
 * @since 5.6.0
 */
public final class APIExecutorMigrationInboundFromPathPut extends AbstractSMPAPIExecutor
{
  private static final Logger LOGGER = LoggerFactory.getLogger (APIExecutorMigrationInboundFromPathPut.class);

  public static void migrationInbound (@NonNull final String sServiceGroupID,
                                       @NonNull final String sMigrationKey,
                                       @NonNull final String sLogPrefix,
                                       @NonNull final IRequestWebScopeWithoutResponse aRequestScope,
                                       @NonNull final PhotonUnifiedResponse aUnifiedResponse) throws SMPServerException
  {
    LOGGER.info (sLogPrefix +
                 "Starting inbound migration for Service Group ID '" +
                 sServiceGroupID +
                 "' and migration key '" +
                 sMigrationKey +
                 "'");

    // Only authenticated user may do so
    final SMPAPICredentials aCredentials = getMandatoryAuth (aRequestScope.headers ());
    final IUser aOwningUser = SMPUserManagerPhoton.validateUserCredentials (aCredentials);

    final ISMPServerAPIDataProvider aDataProvider = new SMPRestDataProvider (aRequestScope);
    final ISMPSettings aSettings = SMPMetaManager.getSettings ();
    final ISMLInfo aSMLInfo = aSettings.getSMLInfo ();
    final IIdentifierFactory aIdentifierFactory = SMPMetaManager.getIdentifierFactory ();
    final ISMPParticipantMigrationManager aParticipantMigrationMgr = SMPMetaManager.getParticipantMigrationMgr ();

    if (aSMLInfo == null)
    {
      throw new SMPPreconditionFailedException ("Currently no SML is available. Please select it in the UI at the 'SMP Settings' page",
                                                aDataProvider.getCurrentURI ());
    }
    if (!aSettings.isSMLEnabled ())
    {
      throw new SMPPreconditionFailedException ("SML Connection is not enabled hence no participant can be migrated",
                                                aDataProvider.getCurrentURI ());
    }

    final IParticipantIdentifier aParticipantID = aIdentifierFactory.parseParticipantIdentifier (sServiceGroupID);
    if (aParticipantID == null)
    {
      // Invalid identifier
      throw SMPBadRequestException.failedToParseSG (sServiceGroupID, aDataProvider.getCurrentURI ());
    }

    // Check that service group does not exist yet, unless it is migrated from this SMP
    if (!SMPInboundMigrationHelper.canMigrateInbound (aParticipantID))
    {
      throw new SMPBadRequestException ("The Service Group '" + sServiceGroupID + "' already exists.",
                                        aDataProvider.getCurrentURI ());
    }

    if (false)
    {
      // Ensure no existing migration is in process
      // This does not make sense, except in test scenarios, but there it is
      // valid
      if (aParticipantMigrationMgr.containsInboundMigration (aParticipantID))
      {
        throw new SMPBadRequestException ("The inbound migration of the Service Group '" +
                                          sServiceGroupID +
                                          "' is already contained.",
                                          aDataProvider.getCurrentURI ());
      }
    }

    // First get the SMK/SML migration result and if that was successful,
    // create the Service Group locally
    try
    {
      final ManageParticipantIdentifierServiceCaller aCaller = SmpSmlHelper.createSMLCallerPI (aSettings.getSMLInfo ());

      // SML call
      aCaller.migrate (aParticipantID, sMigrationKey, SMPServerConfiguration.getSMLSMPID ());
      LOGGER.info (sLogPrefix +
                   "Successfully migrated '" +
                   aParticipantID.getURIEncoded () +
                   "' in the SML to this SMP using migration key '" +
                   sMigrationKey +
                   "'");
    }
    catch (final BadRequestFault | InternalErrorFault | NotFoundFault | UnauthorizedFault | ClientTransportException ex)
    {
      throw new SMPSMLException ("Failed to confirm the migration for participant '" +
                                 aParticipantID.getURIEncoded () +
                                 "' in SML, hence the migration failed." +
                                 " Please check the participant identifier and the migration key.",
                                 ex);
    }

    // Do not allow any Extension here
    final SMPInboundMigrationHelper.Result aResult = SMPInboundMigrationHelper.migrateInboundLocally (aParticipantID,
                                                                                                    sMigrationKey,
                                                                                                    aOwningUser.getID (),
                                                                                                    null);
    final boolean bSGOK = aResult.getServiceGroup () != null;
    final boolean bMigrationOK = aResult.getMigration () != null;
    if (aResult.isParticipantSourceThisSMP ())
    {
      if (aResult.isOutboundMigrationFinalized ())
        LOGGER.info (sLogPrefix +
                     "The outbound Participant Migration with ID '" +
                     aResult.getOutboundMigrationID () +
                     "' for '" +
                     aParticipantID.getURIEncoded () +
                     "' was successfully finalized.");
      else
        LOGGER.error (sLogPrefix +
                      "Failed to finalize the outbound Participant Migration with ID '" +
                      aResult.getOutboundMigrationID () +
                      "' for '" +
                      aParticipantID.getURIEncoded () +
                      "'.");
    }
    else
      if (bSGOK)
        LOGGER.info (sLogPrefix +
                     "The new SMP Service Group for participant '" +
                     aParticipantID.getURIEncoded () +
                     "' was successfully created.");
      else
        LOGGER.error (sLogPrefix +
                      "Error creating the new SMP Service Group for participant '" +
                      aParticipantID.getURIEncoded () +
                      "'.",
                      aResult.getServiceGroupException ());

    if (bMigrationOK)
      LOGGER.info (sLogPrefix +
                   "The participant migration for '" +
                   aParticipantID.getURIEncoded () +
                   "' with migration key '" +
                   sMigrationKey +
                   "' was successfully performed. Please inform the source SMP that the migration was successful.");
    else
      LOGGER.error (sLogPrefix + "Failed to store the participant migration for '" + aParticipantID.getURIEncoded () + "'.");

    final IMicroDocument aResponseDoc = new MicroDocument ();
    final IMicroElement eRoot = aResponseDoc.addElement ("migrationInboundResponse");
    eRoot.setAttribute ("success", bSGOK && bMigrationOK);
    eRoot.setAttribute ("serviceGroupCreated", bSGOK && !aResult.isParticipantSourceThisSMP ());
    eRoot.setAttribute ("migrationCreated", bMigrationOK);
    aUnifiedResponse.xml (aResponseDoc);
  }

  @Override
  protected void invokeAPI (@NonNull final IAPIDescriptor aAPIDescriptor,
                            @NonNull @Nonempty final String sPath,
                            @NonNull final Map <String, String> aPathVariables,
                            @NonNull final IRequestWebScopeWithoutResponse aRequestScope,
                            @NonNull final PhotonUnifiedResponse aUnifiedResponse) throws Exception
  {
    final ISMPServerAPIDataProvider aDataProvider = new SMPRestDataProvider (aRequestScope);

    // Is the writable API disabled?
    if (SMPMetaManager.getSettings ().isRESTWritableAPIDisabled ())
    {
      throw new SMPPreconditionFailedException ("The writable REST API is disabled. migrationInbound will not be executed",
                                                aDataProvider.getCurrentURI ());
    }

    final String sLogPrefix = "[REST API Migration-Inbound] ";
    final String sPathServiceGroupID = aPathVariables.get (SMPRestFilter.PARAM_SERVICE_GROUP_ID);
    final String sPathMigrationKey = aPathVariables.get (SMPRestFilter.PARAM_MIGRATION_KEY);

    migrationInbound (sPathServiceGroupID, sPathMigrationKey, sLogPrefix, aRequestScope, aUnifiedResponse);
  }
}
