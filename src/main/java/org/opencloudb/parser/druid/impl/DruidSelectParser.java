package org.opencloudb.parser.druid.impl;

import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLName;
import com.alibaba.druid.sql.ast.SQLOrderingSpecification;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.*;
import com.alibaba.druid.sql.ast.statement.*;
import com.alibaba.druid.sql.dialect.mysql.ast.expr.MySqlSelectGroupByExpr;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlSelectQueryBlock;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlSelectQueryBlock.Limit;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlUnionQuery;
import com.alibaba.druid.wall.spi.WallVisitorUtils;

import org.opencloudb.MycatServer;
import org.opencloudb.cache.LayerCachePool;
import org.opencloudb.config.ErrorCode;
import org.opencloudb.config.model.SchemaConfig;
import org.opencloudb.config.model.TableConfig;
import org.opencloudb.mpp.MergeCol;
import org.opencloudb.mpp.OrderCol;
import org.opencloudb.route.RouteResultset;
import org.opencloudb.route.util.RouterUtil;

import java.sql.SQLNonTransientException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DruidSelectParser extends DefaultDruidParser {


	@Override
	public void statementParse(SchemaConfig schema, RouteResultset rrs, SQLStatement stmt) {
		SQLSelectStatement selectStmt = (SQLSelectStatement)stmt;
		SQLSelectQuery sqlSelectQuery = selectStmt.getSelect().getQuery();
		if(sqlSelectQuery instanceof MySqlSelectQueryBlock) {
			MySqlSelectQueryBlock mysqlSelectQuery = (MySqlSelectQueryBlock)selectStmt.getSelect().getQuery();

				 parseOrderAggGroupMysql(stmt,rrs, mysqlSelectQuery);
				 //更改canRunInReadDB属性
				 if ((mysqlSelectQuery.isForUpdate() || mysqlSelectQuery.isLockInShareMode()) && rrs.isAutocommit() == false)
				 {
					 rrs.setCanRunInReadDB(false);
				 }

		} else if (sqlSelectQuery instanceof MySqlUnionQuery) { //TODO union语句可能需要额外考虑，目前不处理也没问题
//			MySqlUnionQuery unionQuery = (MySqlUnionQuery)sqlSelectQuery;
//			MySqlSelectQueryBlock left = (MySqlSelectQueryBlock)unionQuery.getLeft();
//			MySqlSelectQueryBlock right = (MySqlSelectQueryBlock)unionQuery.getLeft();
//			System.out.println();
		}
	}
	protected void parseOrderAggGroupMysql( SQLStatement stmt,RouteResultset rrs, MySqlSelectQueryBlock mysqlSelectQuery)
	{
		Map<String, String> aliaColumns = parseAggGroupCommon(stmt,rrs, mysqlSelectQuery);

		//setOrderByCols
		if(mysqlSelectQuery.getOrderBy() != null) {
			List<SQLSelectOrderByItem> orderByItems = mysqlSelectQuery.getOrderBy().getItems();
			rrs.setOrderByCols(buildOrderByCols(orderByItems,aliaColumns));
		}
	}
	protected Map<String, String> parseAggGroupCommon( SQLStatement stmt,RouteResultset rrs, SQLSelectQueryBlock mysqlSelectQuery)
	{
		Map<String, String> aliaColumns = new HashMap<String, String>();
		Map<String, Integer> aggrColumns = new HashMap<String, Integer>();
		List<SQLSelectItem> selectList = mysqlSelectQuery.getSelectList();
		for (int i = 0; i < selectList.size(); i++)
		{
			SQLSelectItem item = selectList.get(i);

			if (item.getExpr() instanceof SQLAggregateExpr)
			{
				SQLAggregateExpr expr = (SQLAggregateExpr) item.getExpr();
				String method = expr.getMethodName();

				//只处理有别名的情况，无别名丢给t添加别名，否则某些数据库会得不到正确结果处理
				int mergeType = MergeCol.getMergeType(method);
				if (MergeCol.MERGE_UNSUPPORT != mergeType)
				{
					if (item.getAlias() != null && item.getAlias().length() > 0)
					{
						aggrColumns.put(item.getAlias(), mergeType);
					} else
					{   //如果不加，jdbc方式时取不到正确结果   ;修改添加别名
							item.setAlias(method + i);
							String sql = stmt.toString();
							rrs.changeNodeSqlAfterAddLimit(sql);
							getCtx().setSql(sql);
							aggrColumns.put(method + i, mergeType);
					}
					rrs.setHasAggrColumn(true);
				}
			} else
			{
				if (!(item.getExpr() instanceof SQLAllColumnExpr))
				{
					String alia = item.getAlias();
					String field = getFieldName(item);
					if (alia == null)
					{
						alia = field;
					}
					aliaColumns.put(field, alia);
				}
			}

		}
		if(aggrColumns.size() > 0) {
			rrs.setMergeCols(aggrColumns);
		}

		//setGroupByCols
		if(mysqlSelectQuery.getGroupBy() != null) {
			List<SQLExpr> groupByItems = mysqlSelectQuery.getGroupBy().getItems();
			String[] groupByCols = buildGroupByCols(groupByItems,aliaColumns);
			rrs.setGroupByCols(groupByCols);
			rrs.setHasAggrColumn(true);
		}
		return aliaColumns;
	}



    private String getFieldName(SQLSelectItem item){
		if ((item.getExpr() instanceof SQLPropertyExpr)||(item.getExpr() instanceof SQLMethodInvokeExpr)
				|| (item.getExpr() instanceof SQLIdentifierExpr)) {			
			return item.getExpr().toString();//字段别名
		}
		else
		  return item.toString();
	}
	/**
	 * 改写sql：需要加limit的加上
	 */
	@Override
	public void changeSql(SchemaConfig schema, RouteResultset rrs, SQLStatement stmt,LayerCachePool cachePool) throws SQLNonTransientException {
		if(isConditionAlwaysTrue(stmt)) {
			ctx.clear();
		}
		//无表的select语句直接路由带任一节点
		if(ctx.getTables() == null || ctx.getTables().size() == 0) {
			rrs = RouterUtil.routeToSingleNode(rrs, schema.getRandomDataNode(), ctx.getSql());
			rrs.setFinishedRoute(true);
			return;
		}
		RouterUtil.tryRouteForTables(schema, ctx, rrs, true,cachePool);
		if(rrs == null) {
			String msg = " find no Route:" + ctx.getSql();
			LOGGER.warn(msg);
			throw new SQLNonTransientException(msg);
		}
		rrs.setFinishedRoute(true);
		
//		if(!isNeedChangeLimit(rrs,schema)){
//			return;
//		}
		
		SQLSelectStatement selectStmt = (SQLSelectStatement)stmt;
		SQLSelectQuery sqlSelectQuery = selectStmt.getSelect().getQuery();
		if(sqlSelectQuery instanceof MySqlSelectQueryBlock) {
			MySqlSelectQueryBlock mysqlSelectQuery = (MySqlSelectQueryBlock)selectStmt.getSelect().getQuery();
			int limitStart = 0;
			int limitSize = schema.getDefaultMaxLimit();
			boolean isNeedAddLimit = isNeedAddLimit(schema, rrs, mysqlSelectQuery);
			if(isNeedAddLimit) {
				Limit limit = new Limit();
				limit.setRowCount(new SQLIntegerExpr(limitSize));
				mysqlSelectQuery.setLimit(limit);
				String nativeSql= convertLimitToNativePageSql(rrs, stmt, getCtx().getSql(), 0, limitSize, isNeedAddLimit);
				rrs.changeNodeSqlAfterAddLimit(nativeSql);
			}
			Limit limit = mysqlSelectQuery.getLimit();
			if(limit != null) {
				SQLIntegerExpr offset = (SQLIntegerExpr)limit.getOffset();
				SQLIntegerExpr count = (SQLIntegerExpr)limit.getRowCount();
				if(offset != null) {
					limitStart = offset.getNumber().intValue();
					rrs.setLimitStart(limitStart);
				} 
				if(count != null) {
					limitSize = count.getNumber().intValue();
					rrs.setLimitSize(limitSize);
				}

				if(isNeedChangeLimit(rrs)) {
					Limit changedLimit = new Limit();
					changedLimit.setRowCount(new SQLIntegerExpr(limitStart + limitSize));
					
					if(offset != null) {
						if(limitStart < 0) {
							String msg = "You have an error in your SQL syntax; check the manual that " +
									"corresponds to your MySQL server version for the right syntax to use near '" + limitStart + "'";
							throw new SQLNonTransientException(ErrorCode.ER_PARSE_ERROR + " - " + msg);
						} else {
							changedLimit.setOffset(new SQLIntegerExpr(0));
							//TODO
						}
					}
					
					mysqlSelectQuery.setLimit(changedLimit);
                    //取原始sql，否则会导致部分非mysql的库的语法错误解析，比如字符串连接符
                    String nativeSql= convertLimitToNativePageSql(rrs, stmt, getCtx().getSql(), 0, limitStart + limitSize, false);
					rrs.changeNodeSqlAfterAddLimit(nativeSql);

					//设置改写后的sql
					ctx.setSql(nativeSql);

				}   else
				{
					//单节点需要转换limit 为本地分页
                    String sql=getCtx().getSql(); //取原始sql，否则会导致部分非mysql的库的语法错误解析，比如字符串连接符
                    String nativeSql= convertLimitToNativePageSql(rrs, stmt, sql, rrs.getLimitStart(), rrs.getLimitSize(), false);
                    if(!nativeSql.equalsIgnoreCase(sql))
                    {
                        rrs.changeNodeSqlAfterAddLimit(nativeSql);
						ctx.setSql(nativeSql);
                    }
				}
				

			}
			
			rrs.setCacheAble(isNeedCache(schema, rrs, mysqlSelectQuery));
		}
		
	}


	protected String convertLimitToNativePageSql(RouteResultset rrs, SQLStatement stmt, String sql, int offset, int count, boolean isNeedAddLimit)
	{
		if(!isNeedChangeLimit(rrs)&&!isNeedAddLimit)
		{
		  return sql;//mysql 单节点直接返回
		}
	 return stmt.toString();     //mysql可以直接输出
	}


	
	protected boolean isNeedChangeLimit(RouteResultset rrs) {
		if(rrs.getNodes() == null) {
			return false;
		} else {
			if(rrs.getNodes().length > 1) {
				return true;
			}
			return false;
		
		} 
	}
	
	private boolean isNeedCache(SchemaConfig schema, RouteResultset rrs, MySqlSelectQueryBlock mysqlSelectQuery) {
		if(ctx.getTables() == null || ctx.getTables().size() == 0 ) {
			return false;
		}
		TableConfig tc = schema.getTables().get(ctx.getTables().get(0));
		if((ctx.getTables().size() == 1 && tc.isGlobalTable())
				) {//|| (ctx.getTables().size() == 1) && tc.getRule() == null && tc.getDataNodes().size() == 1
			return false;
		} else {
			//单表主键查询
			if(ctx.getTables().size() == 1) {
				String tableName = ctx.getTables().get(0);
				String primaryKey = schema.getTables().get(tableName).getPrimaryKey();
//				schema.getTables().get(ctx.getTables().get(0)).getParentKey() != null;
				if(ctx.getTablesAndConditions().get(tableName) != null
						&& ctx.getTablesAndConditions().get(tableName).get(primaryKey) != null 
						&& tc.getDataNodes().size() > 1) {//有主键条件
					return false;
				} 
			}
			return true;
		}
	}
	
	/**
	 * 单表且是全局表
	 * 单表且rule为空且nodeNodes只有一个
	 * @param schema
	 * @param rrs
	 * @param mysqlSelectQuery
	 * @return
	 */
	private boolean isNeedAddLimit(SchemaConfig schema, RouteResultset rrs, MySqlSelectQueryBlock mysqlSelectQuery) {
//		ctx.getTablesAndConditions().get(key))
		
		if(schema.getDefaultMaxLimit() == -1) {
			return false;
		} else if (mysqlSelectQuery.getLimit() != null) {//语句中已有limit
			return false;
		} else if(ctx.getTables().size() == 1) {
			String tableName = ctx.getTables().get(0);
			boolean isNeedAddLimit=schema.getTables().get(tableName).isNeedAddLimit();
			if(!isNeedAddLimit)
			{
				return false;//优先从配置文件取
			}

			if(schema.getTables().get(tableName).isGlobalTable()) {
				return true;//TODO
			}

			String primaryKey = schema.getTables().get(tableName).getPrimaryKey();

//			schema.getTables().get(ctx.getTables().get(0)).getParentKey() != null;
			if(ctx.getTablesAndConditions().get(tableName) == null) {//无条件
				return true;
			}
			
			if (ctx.getTablesAndConditions().get(tableName).get(primaryKey) != null) {//条件中带主键
				return false;
			}
			return true;
		} else if(rrs.hasPrimaryKeyToCache() && ctx.getTables().size() == 1){//只有一个表且条件中有主键,不需要limit了,因为主键只能查到一条记录
			return false;
		} else {//多表或无表
			return false;
		}
		
	}
	private String getAliaColumn(Map<String, String> aliaColumns,String column ){
		String alia=aliaColumns.get(column);
		if (alia==null){
			return column;
		}
		else {
			return alia;
		}
	}
	
	private String[] buildGroupByCols(List<SQLExpr> groupByItems,Map<String, String> aliaColumns) {
		String[] groupByCols = new String[groupByItems.size()]; 
		for(int i= 0; i < groupByItems.size(); i++) {
			SQLExpr expr = ((MySqlSelectGroupByExpr)groupByItems.get(i)).getExpr();			
			String column; 
			if (expr instanceof SQLName) {
				column= removeBackquote(((SQLName)expr).getSimpleName());//不要转大写 2015-2-10 sohudo removeBackquote(expr.getSimpleName().toUpperCase());
			}
			else {
				column= removeBackquote(expr.toString());
			}
			groupByCols[i] = getAliaColumn(aliaColumns,column);//column;
		}
		return groupByCols;
	}
	
	protected LinkedHashMap<String, Integer> buildOrderByCols(List<SQLSelectOrderByItem> orderByItems,Map<String, String> aliaColumns) {
		LinkedHashMap<String, Integer> map = new LinkedHashMap<String, Integer>();
		for(int i= 0; i < orderByItems.size(); i++) {
			SQLOrderingSpecification type = orderByItems.get(i).getType();
            //orderColumn只记录字段名称,因为返回的结果集是不带表名的。
			SQLExpr expr =  orderByItems.get(i).getExpr();
			String col;
			if (expr instanceof SQLName) {
			   col = ((SQLName)expr).getSimpleName();
			}
			else {
				col =expr.toString();
			}
			if(type == null) {
				type = SQLOrderingSpecification.ASC;
			}
			col=getAliaColumn(aliaColumns,col);
			map.put(col, type == SQLOrderingSpecification.ASC ? OrderCol.COL_ORDER_TYPE_ASC : OrderCol.COL_ORDER_TYPE_DESC);
		}
		return map;
	}
	
	private boolean isConditionAlwaysTrue(SQLStatement statement) {
		SQLSelectStatement selectStmt = (SQLSelectStatement)statement;
		SQLSelectQuery sqlSelectQuery = selectStmt.getSelect().getQuery();
		if(sqlSelectQuery instanceof MySqlSelectQueryBlock) {
			MySqlSelectQueryBlock mysqlSelectQuery = (MySqlSelectQueryBlock)selectStmt.getSelect().getQuery();
			SQLExpr expr = mysqlSelectQuery.getWhere();
			
			Object o = WallVisitorUtils.getValue(expr);
			if(Boolean.TRUE.equals(o)) {
				return true;
			}
			return false;
		} else {//union
			return false;
		}
		
	}

	protected void setLimitIFChange(SQLStatement stmt, RouteResultset rrs, SchemaConfig schema, SQLBinaryOpExpr one, int firstrownum, int lastrownum)
	{
		rrs.setLimitStart(firstrownum);
		rrs.setLimitSize(lastrownum - firstrownum);
		LayerCachePool tableId2DataNodeCache = (LayerCachePool) MycatServer.getInstance().getCacheService().getCachePool("TableID2DataNodeCache");
		try
		{
			RouterUtil.tryRouteForTables(schema, getCtx(), rrs, true, tableId2DataNodeCache);
		} catch (SQLNonTransientException e)
		{
			throw new RuntimeException(e);
		}
		if (isNeedChangeLimit(rrs))
		{
			one.setRight(new SQLIntegerExpr(0));
			rrs.changeNodeSqlAfterAddLimit(stmt.toString());
			//设置改写后的sql
			getCtx().setSql(stmt.toString());
		}
	}
}
