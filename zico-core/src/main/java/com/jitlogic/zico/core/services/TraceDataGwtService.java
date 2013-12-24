/**
 * Copyright 2012-2013 Rafal Lewczuk <rafal.lewczuk@jitlogic.com>
 * <p/>
 * This is free software. You can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 * <p/>
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * <p/>
 * You should have received a copy of the GNU General Public License
 * along with this software. If not, see <http://www.gnu.org/licenses/>.
 */
package com.jitlogic.zico.core.services;

import com.google.inject.Singleton;
import com.jitlogic.zico.core.HostStore;
import com.jitlogic.zico.core.HostStoreManager;
import com.jitlogic.zico.core.model.TraceInfoRecord;
import com.jitlogic.zico.core.TraceRecordStore;
import com.jitlogic.zico.core.ZicoRuntimeException;
import com.jitlogic.zico.core.eql.Parser;
import com.jitlogic.zico.core.model.MethodRankInfo;
import com.jitlogic.zico.core.model.TraceInfoSearchQuery;
import com.jitlogic.zico.core.model.TraceRecordSearchQuery;
import com.jitlogic.zico.core.model.TraceInfoSearchResult;
import com.jitlogic.zico.core.model.TraceRecordInfo;
import com.jitlogic.zico.core.model.TraceRecordSearchResult;
import com.jitlogic.zico.core.search.EqlTraceRecordMatcher;
import com.jitlogic.zico.core.search.FullTextTraceRecordMatcher;
import com.jitlogic.zico.core.search.TraceRecordMatcher;
import com.jitlogic.zorka.common.tracedata.TraceRecord;

import javax.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

@Singleton
public class TraceDataGwtService {
    private HostStoreManager storeManager;


    @Inject
    public TraceDataGwtService(HostStoreManager storeManager) {
        this.storeManager = storeManager;
    }


    public TraceInfoSearchResult searchTraces(TraceInfoSearchQuery query) {
        try {
            HostStore host = storeManager.getHost(query.getHostName(), false);
            if (host == null) {
                throw new ZicoRuntimeException("Unknown host: " + query.getHostName());
            }
            return host.search(query);
        } catch (IOException e) {
            throw new ZicoRuntimeException("Error while searching: " + query, e);
        }
    }


    public List<MethodRankInfo> traceMethodRank(String hostName, long traceOffs, String orderBy, String orderDesc) {
        HostStore host = storeManager.getHost(hostName, false);
        if (host != null) {
            TraceInfoRecord info = host.getInfoRecord(traceOffs);
            if (info != null && host.getTraceDataStore() != null) {
                return host.getTraceDataStore().methodRank(info, orderBy, orderDesc);
            }
        }
        return Collections.EMPTY_LIST;
    }


    public TraceRecordInfo getRecord(String hostName, long traceOffs, long minTime, String path) {

        HostStore host = storeManager.getHost(hostName, false);
        if (host != null) {
            TraceInfoRecord info = host.getInfoRecord(traceOffs);
            if (info != null) {
                return host.getTraceDataStore().packTraceRecord(
                        host.getTraceDataStore().getTraceRecord(info, path, minTime), path, null);
            }
        }
        return null;
    }


    public List<TraceRecordInfo> listRecords(String hostName, long traceOffs, long minTime, String path, boolean recursive) {

        HostStore host = storeManager.getHost(hostName, false);
        if (host != null) {
            TraceInfoRecord info = host.getInfoRecord(traceOffs);
            TraceRecordStore ctx = host.getTraceDataStore();
            if (info != null && ctx != null) {
                TraceRecord tr = ctx.getTraceRecord(info, path, minTime);

                List<TraceRecordInfo> lst = new ArrayList<>();

                if (path != null) {
                    packRecords(path, ctx, tr, lst, recursive);
                } else {
                    lst.add(ctx.packTraceRecord(tr, "", 250));
                    if (recursive) {
                        packRecords("", ctx, tr, lst, recursive);
                    }
                }
                return lst;
            }
        }

        return Collections.EMPTY_LIST;
    }


    private void packRecords(String path, TraceRecordStore ctx, TraceRecord tr, List<TraceRecordInfo> lst, boolean recursive) {
        for (int i = 0; i < tr.numChildren(); i++) {
            TraceRecord child = tr.getChild(i);
            String childPath = path.length() > 0 ? (path + "/" + i) : "" + i;
            lst.add(ctx.packTraceRecord(child, childPath, 250));
            if (recursive && child.numChildren() > 0) {
                packRecords(childPath, ctx, child, lst, recursive);
            }
        }
    }


    public TraceRecordSearchResult searchRecords(String hostName, long traceOffs, long minTime, String path,
                                                 TraceRecordSearchQuery expr) {

        HostStore host = storeManager.getHost(hostName, false);
        if (host != null) {
            TraceInfoRecord info = host.getInfoRecord(traceOffs);
            TraceRecordStore ctx = host.getTraceDataStore();
            if (ctx != null && info != null) {
                TraceRecord tr = ctx.getTraceRecord(info, path, minTime);
                TraceRecordSearchResult result = new TraceRecordSearchResult();
                result.setResult(new ArrayList<TraceRecordInfo>());
                result.setMinTime(Long.MAX_VALUE);
                result.setMaxTime(Long.MIN_VALUE);

                TraceRecordMatcher matcher;
                String se = expr.getSearchExpr();
                switch (expr.getType()) {
                    case TraceRecordSearchQuery.TXT_QUERY:
                        if (se != null && se.startsWith("~")) {
                            int rflag = 0 != (expr.getFlags() & TraceRecordSearchQuery.IGNORE_CASE) ? Pattern.CASE_INSENSITIVE : 0;
                            Pattern regex = Pattern.compile(se.substring(1, se.length()), rflag);
                            matcher = new FullTextTraceRecordMatcher(host.getSymbolRegistry(), expr.getFlags(), regex);
                        } else {
                            matcher = new FullTextTraceRecordMatcher(host.getSymbolRegistry(), expr.getFlags(), se);
                        }
                        break;
                    case TraceRecordSearchQuery.EQL_QUERY:
                        matcher = new EqlTraceRecordMatcher(host.getSymbolRegistry(), Parser.expr(se), expr.getFlags(), tr.getTime());
                        break;
                    default:
                        throw new ZicoRuntimeException("Illegal search expression type: " + expr.getType());
                }
                ctx.searchRecords(tr, path, matcher, result, tr.getTime(), false);

                if (result.getMinTime() == Long.MAX_VALUE) {
                    result.setMinTime(0);
                }

                if (result.getMaxTime() == Long.MIN_VALUE) {
                    result.setMaxTime(0);
                }

                return result;
            }
        }
        return null;
    }

}
