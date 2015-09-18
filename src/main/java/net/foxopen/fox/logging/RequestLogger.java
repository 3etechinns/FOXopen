package net.foxopen.fox.logging;

import net.foxopen.fox.XFUtil;
import net.foxopen.fox.auth.AuthUtil;
import net.foxopen.fox.database.ConnectionAgent;
import net.foxopen.fox.database.UCon;
import net.foxopen.fox.database.UConBindMap;
import net.foxopen.fox.database.parser.ParsedStatement;
import net.foxopen.fox.dom.DOM;
import net.foxopen.fox.entrypoint.CookieBasedFoxSession;
import net.foxopen.fox.entrypoint.FoxGlobals;
import net.foxopen.fox.ex.ExDB;
import net.foxopen.fox.ex.ExInternal;
import net.foxopen.fox.ex.ExServiceUnavailable;
import net.foxopen.fox.job.BasicFoxJobPool;
import net.foxopen.fox.job.FoxJobTask;
import net.foxopen.fox.job.TaskCompletionMessage;
import net.foxopen.fox.sql.SQLManager;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;

public class RequestLogger {

  public static final boolean LOG_USER_EXPERIENCE_TIMES = true;

  private static final String INSERT_LOG_FILENAME = "InsertRequestLog.sql";
  private static final String UPDATE_LOG_FILENAME = "UpdateRequestLog.sql";
  private static final String UPDATE_LOG_UX_TIME_FILENAME = "UpdateRequestLogUXTime.sql";

  private static final RequestLogger INSTANCE = new RequestLogger();

  private static final Iterator<String> REQUEST_ID_ITERATOR = XFUtil.getUniqueIterator();

  private static Set<Pattern> EXCLUDE_URI_PATTERNS = new HashSet<>();
  static {
    EXCLUDE_URI_PATTERNS.add(Pattern.compile("^.*/upload/status$"));
  }

  private final BasicFoxJobPool mLogWriterJobPool = BasicFoxJobPool.createSingleThreadedPool("RequestLogger");

  public static RequestLogger instance() {
    return INSTANCE;
  }

  private RequestLogger() {
  }

  public String startRequestLog(final HttpServletRequest pHttpRequest) {

    //Read request info now so it doesn't mutate before being written to the log
    final String lRequestLogId = REQUEST_ID_ITERATOR.next();
    final String lRequestURI = pHttpRequest.getRequestURI();
    final Date lRequestTime = new Date();
    final String lHttpMethod = pHttpRequest.getMethod();
    String lQS = XFUtil.nvl(pHttpRequest.getQueryString());
    final String lQueryString = lQS.substring(0, Math.min(lQS.length(), 4000));
    final String lUserAgent = pHttpRequest.getHeader("User-Agent");
    final String lRemotAddr = pHttpRequest.getRemoteAddr();
    final String lForwardedFor = pHttpRequest.getHeader(AuthUtil.X_FORWARDED_FOR_HEADER_NAME);
    final String lSessionId = CookieBasedFoxSession.getSessionIdFromRequest(pHttpRequest);

    mLogWriterJobPool.submitTask(new FoxJobTask() {
      public TaskCompletionMessage executeTask() {
        if(!isURIExcluded(lRequestURI)) {
          ParsedStatement lInsertStatement = SQLManager.instance().getStatement(INSERT_LOG_FILENAME, getClass());
          UConBindMap lBindMap = new UConBindMap()
          .defineBind(":id", lRequestLogId)
          .defineBind(":server_hostname", FoxGlobals.getInstance().getServerHostName())
          .defineBind(":server_context", FoxGlobals.getInstance().getContextPath())
          .defineBind(":request_uri", lRequestURI)
          .defineBind(":request_start_timestamp", lRequestTime)
          .defineBind(":http_method", lHttpMethod)
          .defineBind(":query_string", lQueryString)
          .defineBind(":user_agent", lUserAgent)
          .defineBind(":fox_session_id", lSessionId)
          .defineBind(":origin_ip", lRemotAddr)
          .defineBind(":forwarded_for", lForwardedFor);

          try (UCon lUCon = ConnectionAgent.getConnection(FoxGlobals.getInstance().getEngineConnectionPoolName(), "Request Log Insert")) {
            lUCon.executeAPI(lInsertStatement, lBindMap);
            lUCon.commit();
            //UCon closed by try-with-resources
          }
          catch (ExServiceUnavailable | ExDB e) {
            throw new ExInternal("Start request log failed for request " + lRequestLogId, e);
          }

          return new TaskCompletionMessage(this, "Request log start written for request " + lRequestLogId);
        }
        else {
          return new TaskCompletionMessage(this, "Request log start skipped to due exclusion pattern match for URI " + lRequestURI + ", request " + lRequestLogId);
        }
      }

      @Override
      public String getTaskDescription() {
        return "RequestLogStart";
      }
    });

    return lRequestLogId;
  }

  public void endRequestLog(final String pRequestLogId, HttpServletRequest pOriginalRequest, HttpServletResponse pOptionalResponse) {

    final String lRequestURI = pOriginalRequest.getRequestURI();
    final Date lEndTime = new Date();
    final Integer lResponseCode = pOptionalResponse != null ? pOptionalResponse.getStatus() : null;

    mLogWriterJobPool.submitTask(new FoxJobTask() {
      public TaskCompletionMessage executeTask() {

        if(!isURIExcluded(lRequestURI)) {
          ParsedStatement lUpdateStatement = SQLManager.instance().getStatement(UPDATE_LOG_FILENAME, getClass());
          UConBindMap lBindMap = new UConBindMap()
          .defineBind(":request_end_timestamp", lEndTime)
          .defineBind(":response_code", lResponseCode)
          .defineBind(":id", pRequestLogId);

          try (UCon lUCon = ConnectionAgent.getConnection(FoxGlobals.getInstance().getEngineConnectionPoolName(), "Request Log Update")) {
            lUCon.executeAPI(lUpdateStatement, lBindMap);
            lUCon.commit();
            //UCon closed by try-with-resources
          }
          catch (ExServiceUnavailable | ExDB e) {
            throw new ExInternal("End request log failed for request " + pRequestLogId, e);
          }

          return new TaskCompletionMessage(this, "Request log finalise written for request " + pRequestLogId);
        }
        else {
          return new TaskCompletionMessage(this, "Request log end skipped to due exclusion pattern match for URI " + lRequestURI + ", request " + pRequestLogId);
        }
      }

      @Override
      public String getTaskDescription() {
        return "RequestLogFinalise";
      }
    });
  }

  private boolean isURIExcluded(String pRequestURI) {
    for(Pattern lPattern : EXCLUDE_URI_PATTERNS) {
      if(lPattern.matcher(pRequestURI).matches()) {
        return true;
      }
    }
    return false;
  }

  void logUserExperienceTime(final String pRequestId, final long pExperienceTimeMS, final DOM pXMLData) {

    mLogWriterJobPool.submitTask(new FoxJobTask() {
      @Override
      public TaskCompletionMessage executeTask() {
        ParsedStatement lUpdateStatement = SQLManager.instance().getStatement(UPDATE_LOG_UX_TIME_FILENAME, getClass());
        UConBindMap lBindMap = new UConBindMap()
          .defineBind(":user_experience_time_ms", pExperienceTimeMS)
          .defineBind(":user_experience_detail_xml", pXMLData)
          .defineBind(":id", pRequestId);

        try (UCon lUCon = ConnectionAgent.getConnection(FoxGlobals.getInstance().getEngineConnectionPoolName(), "Request Log UX Time Update")) {
          lUCon.executeAPI(lUpdateStatement, lBindMap);
          lUCon.commit();
          //UCon closed by try-with-resources
        }
        catch (ExServiceUnavailable | ExDB e) {
          throw new ExInternal("Set UX time failed for request " + pRequestId, e);
        }

        return new TaskCompletionMessage(this, "Request log UX time written for request " + pRequestId);
      }

      @Override
      public String getTaskDescription() {
        return "RequestLogUX";
      }
    });
  }
}
