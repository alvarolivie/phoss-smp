/*
 * Copyright (C) 2015-2026 Philip Helger and contributors
 * philip[at]helger[dot]com
 *
 * The Original Code is Copyright The Peppol project (http://www.peppol.eu)
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.helger.phoss.smp.domain.pmigration;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import com.helger.annotation.concurrent.Immutable;
import com.helger.peppolid.IParticipantIdentifier;
import com.helger.phoss.smp.domain.SMPMetaManager;
import com.helger.phoss.smp.domain.servicegroup.ISMPServiceGroup;
import com.helger.phoss.smp.domain.servicegroup.ISMPServiceGroupManager;

/**
 * Shared local part of an inbound participant migration, used by the UI and the REST API. A
 * participant of this SMP can be migrated to this SMP, if an outbound migration for it is in
 * progress. That closes the outbound migration at the SML.
 *
 * @author Philip Helger
 * @since 8.6.2
 */
@Immutable
public final class SMPInboundMigrationHelper
{
  public static final class Result
  {
    private final ISMPServiceGroup m_aServiceGroup;
    private final Exception m_aServiceGroupException;
    private final ISMPParticipantMigration m_aMigration;
    private final String m_sOutboundMigrationID;
    private final boolean m_bOutboundMigrationFinalized;

    Result (@Nullable final ISMPServiceGroup aServiceGroup,
            @Nullable final Exception aServiceGroupException,
            @Nullable final ISMPParticipantMigration aMigration,
            @Nullable final String sOutboundMigrationID,
            final boolean bOutboundMigrationFinalized)
    {
      m_aServiceGroup = aServiceGroup;
      m_aServiceGroupException = aServiceGroupException;
      m_aMigration = aMigration;
      m_sOutboundMigrationID = sOutboundMigrationID;
      m_bOutboundMigrationFinalized = bOutboundMigrationFinalized;
    }

    @Nullable
    public ISMPServiceGroup getServiceGroup ()
    {
      return m_aServiceGroup;
    }

    @Nullable
    public Exception getServiceGroupException ()
    {
      return m_aServiceGroupException;
    }

    @Nullable
    public ISMPParticipantMigration getMigration ()
    {
      return m_aMigration;
    }

    public boolean isParticipantSourceThisSMP ()
    {
      return m_sOutboundMigrationID != null;
    }

    @Nullable
    public String getOutboundMigrationID ()
    {
      return m_sOutboundMigrationID;
    }

    public boolean isOutboundMigrationFinalized ()
    {
      return m_bOutboundMigrationFinalized;
    }
  }

  private SMPInboundMigrationHelper ()
  {}

  @Nullable
  private static ISMPParticipantMigration _getOutboundMigrationInProgress (@NonNull final IParticipantIdentifier aParticipantID)
  {
    return SMPMetaManager.getParticipantMigrationMgr ()
                         .getParticipantMigrationOfParticipantID (EParticipantMigrationDirection.OUTBOUND,
                                                                  EParticipantMigrationState.IN_PROGRESS,
                                                                  aParticipantID);
  }

  /**
   * @param aParticipantID
   *        The participant to be migrated. May not be <code>null</code>.
   * @return <code>true</code> if the participant does not exist on this SMP, or if an outbound
   *         migration for it is in progress.
   */
  public static boolean canMigrateInbound (@NonNull final IParticipantIdentifier aParticipantID)
  {
    return !SMPMetaManager.getServiceGroupMgr ().containsSMPServiceGroupWithID (aParticipantID) ||
           _getOutboundMigrationInProgress (aParticipantID) != null;
  }

  /**
   * Perform the local part of an inbound migration, after <code>Migrate()</code> succeeded at the
   * SML: create the Service Group (or keep it, if the participant is from this SMP), store the
   * inbound migration and finalize a pending outbound migration.
   *
   * @param aParticipantID
   *        The migrated participant. May not be <code>null</code>.
   * @param sMigrationKey
   *        The migration key used. May not be <code>null</code>.
   * @param sOwningUserID
   *        The owner of a newly created Service Group. May not be <code>null</code>.
   * @param sExtension
   *        The extension of a newly created Service Group. May be <code>null</code>.
   * @return The result. Never <code>null</code>.
   */
  @NonNull
  public static Result migrateInboundLocally (@NonNull final IParticipantIdentifier aParticipantID,
                                              @NonNull final String sMigrationKey,
                                              @NonNull final String sOwningUserID,
                                              @Nullable final String sExtension)
  {
    final ISMPServiceGroupManager aServiceGroupMgr = SMPMetaManager.getServiceGroupMgr ();
    final ISMPParticipantMigrationManager aParticipantMigrationMgr = SMPMetaManager.getParticipantMigrationMgr ();

    final boolean bExists = aServiceGroupMgr.containsSMPServiceGroupWithID (aParticipantID);
    final ISMPParticipantMigration aOutboundMigration = bExists ? _getOutboundMigrationInProgress (aParticipantID) : null;

    ISMPServiceGroup aSG = null;
    Exception aCaughtEx = null;
    if (aOutboundMigration != null)
    {
      aSG = aServiceGroupMgr.getSMPServiceGroupOfID (aParticipantID);
    }
    else
    {
      try
      {
        // Do NOT create in SMK/SML
        aSG = aServiceGroupMgr.createSMPServiceGroup (sOwningUserID, aParticipantID, sExtension, null, false);
      }
      catch (final Exception ex)
      {
        aCaughtEx = ex;
      }
    }

    final ISMPParticipantMigration aMigration = aParticipantMigrationMgr.createInboundParticipantMigration (aParticipantID,
                                                                                                            sMigrationKey);

    boolean bOutboundMigrationFinalized = false;
    if (aOutboundMigration != null)
    {
      bOutboundMigrationFinalized = aParticipantMigrationMgr.setParticipantMigrationState (aOutboundMigration.getID (),
                                                                                           EParticipantMigrationState.MIGRATED)
                                                            .isChanged ();
    }

    return new Result (aSG,
                       aCaughtEx,
                       aMigration,
                       aOutboundMigration == null ? null : aOutboundMigration.getID (),
                       bOutboundMigrationFinalized);
  }
}
