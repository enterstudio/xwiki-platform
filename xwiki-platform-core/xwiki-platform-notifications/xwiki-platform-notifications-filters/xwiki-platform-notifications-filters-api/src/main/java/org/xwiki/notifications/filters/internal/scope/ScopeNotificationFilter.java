/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.notifications.filters.internal.scope;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.eventstream.Event;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.notifications.NotificationFormat;
import org.xwiki.notifications.filters.NotificationFilter;
import org.xwiki.notifications.filters.NotificationFilterType;
import org.xwiki.notifications.filters.expression.ExpressionNode;
import org.xwiki.notifications.preferences.NotificationPreference;
import org.xwiki.notifications.preferences.NotificationPreferenceCategory;
import org.xwiki.notifications.preferences.NotificationPreferenceProperty;

/**
 * Define a notification filter based on a scope in the wiki.
 *
 * @version $Id$
 * @since 9.9RC1
 */
@Component
@Named(ScopeNotificationFilter.FILTER_NAME)
@Singleton
public class ScopeNotificationFilter implements NotificationFilter
{
    /**
     * Name of the filter.
     */
    public static final String FILTER_NAME = "scopeNotificationFilter";

    @Inject
    private ScopeNotificationFilterLocationStateComputer stateComputer;

    @Inject
    private ScopeNotificationFilterExpressionGenerator expressionGenerator;

    @Override
    public boolean filterEvent(Event event, DocumentReference user, NotificationFormat format)
    {
        final EntityReference eventEntity = getEventEntity(event);
        if (eventEntity == null) {
            // We don't handle events that are not related to a particular location
            return false;
        }

        // We dismiss the event if the location is not watched
        return !stateComputer.isLocationWatched(user, eventEntity, event.getType(), format);
    }

    @Override
    public boolean matchesPreference(NotificationPreference preference)
    {
        return preference.getCategory().equals(NotificationPreferenceCategory.DEFAULT)
                && preference.getProperties().containsKey(NotificationPreferenceProperty.EVENT_TYPE);
    }

    @Override
    public ExpressionNode filterExpression(DocumentReference user, NotificationPreference preference)
    {
        return expressionGenerator.filterExpression(user,
                (String) preference.getProperties().get(NotificationPreferenceProperty.EVENT_TYPE),
                preference.getFormat());
    }

    @Override
    public ExpressionNode filterExpression(DocumentReference user, NotificationFilterType type,
            NotificationFormat format)
    {
        // We don't handle this use-case anymore
        return null;
    }

    @Override
    public String getName()
    {
        return FILTER_NAME;
    }

    private EntityReference getEventEntity(Event event)
    {
        if (event.getDocument() != null) {
            return event.getDocument();
        }
        if (event.getSpace() != null) {
            return event.getSpace();
        }
        return event.getWiki();
    }
}
