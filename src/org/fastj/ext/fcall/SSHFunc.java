package org.fastj.ext.fcall;
/*
 * Copyright 2015  FastJ
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */



import java.util.HashMap;

import org.fastj.fit.intf.DataInvalidException;
import org.fastj.fit.intf.FuncResponse;
import org.fastj.fit.intf.ICloseable;
import org.fastj.fit.intf.IFuncCall;
import org.fastj.fit.intf.ParamIncertitudeException;
import org.fastj.fit.intf.ParameterTable;
import org.fastj.fit.intf.TContext;
import org.fastj.net.api.NVar;
import org.fastj.net.api.Response;
import org.fastj.net.api.SshConnection;
import org.fastj.net.impl.JSchImpl;
import org.fastj.net.protocol.Protocol;
import static org.fastj.fit.tool.StringUtil.*;

/**
 * 
 * @command ssh_connect(ip, port, user, pass)
 * @command ssh_connect(ip, port, user, keystore, pass)
 * @command ssh_exec(cmd)
 * @command ssh_close()
 * 
 * @param autosend="password: ", "pass\n"
 * @param ends=>, ]
 * @param timeout=1000
 * @param clean_setting=true
 * 
 * @author zhouqingquan
 *
 */
public class SSHFunc implements IFuncCall{

	private String name;
	
	public SSHFunc(String func)
	{
		this.name = func;
	}
	
	@Override
	public String name() {
		return name;
	}

	@Override
	public FuncResponse run(TContext ctx, ParameterTable table, String argStr) throws ParamIncertitudeException, DataInvalidException {
		String connId = "__ssh_connection__";
		SSHCloseableResource sshRef = null;
		
		int timeout = 15000;
		switch(name)
		{
		case "ssh_connect":
			Protocol ptc = ProtocolTool.getSSHProtocol(argStr, table);
			if (ptc == null) throw new DataInvalidException("Cannot create SSH Protocol: " + argStr);
			timeout = Integer.valueOf(expend(table.getPara("timeout", "15000"), table));
			ptc.setTimeout(timeout);
			
			SshConnection ssh = new JSchImpl();
			setConn(ssh, table);
			Response<String> sshresp = ssh.open(ptc);
			FuncResponse fr = new FuncResponse();
			fr.setRequest("ssh_connect " + ptc.toString());
			connId = table.getParent().lcontains("ssh_id") ? trim(expendVar("ssh_id", table.getParent())) : "__ssh_connection__";
			if (ssh.isConnected())
			{
				ctx.put(connId, new SSHCloseableResource(ssh));
				
				fr.setCode(Response.OK);
				HashMap<String, Object> entity = new HashMap<String, Object>();
				entity.put("code", 0);
				entity.put("message", "SSH Connected.");
				fr.setEntity(entity);
				return fr;
			}
			else
			{
				fr.setCode(sshresp.getCode());
				fr.setPhrase(sshresp.getPhrase());
				HashMap<String, Object> entity = new HashMap<String, Object>();
				entity.put("code", sshresp.getCode());
				entity.put("message", sshresp.getPhrase());
				fr.setEntity(entity);
				
				return fr;
			}
		case "ssh_cmd":
		case "ssh_exec":
			String cmd = expend(argStr, table);
			connId = table.getParent().lcontains("ssh_id") 
					? trim(expendVar("ssh_id", table.getParent())) 
					: "__ssh_connection__";
			
			sshRef = ((SSHCloseableResource) ctx.get(connId));
			if (sshRef == null)
			{
				FuncResponse exefr = new FuncResponse();
				exefr.setRequest(name + " " + cmd);
				HashMap<String, Object> entity = new HashMap<String, Object>();
				entity.put("content", "");
				entity.put("code", Response.INVALID_PARAM);
				exefr.setEntity(entity);
				exefr.setCode(Response.INVALID_PARAM);
				exefr.setPhrase("Cannot find ssh connection, id="+ connId);
				return exefr;
			}
			
			SshConnection ssh_conn = sshRef.getSsh();
			
			if (ssh_conn != null)
			{
				setConn(ssh_conn, table);
				timeout = 15000;
				try {
					timeout = Integer.valueOf(expend(table.getPara("timeout", "15000"), table));
				} catch (NumberFormatException e) {
					throw new DataInvalidException("Var 'timeout' is not a number.");
				}
				Response<String> resp = "ssh_exec".equals(name) ? ssh_conn.exec(timeout, cmd) : ssh_conn.cmd(timeout, cmd);
				FuncResponse exefr = new FuncResponse();
				exefr.setCode(resp.getCode());
				exefr.setRequest(name + " " + cmd);
				HashMap<String, Object> entity = new HashMap<String, Object>();
				entity.put("content", "ssh_exec".equals(name) ? trimExecLine(resp.getEntity()) : resp.getEntity());
				entity.put("message", resp.getPhrase());
				entity.put("code", resp.getCode());
				
				exefr.setEntity(entity);
				exefr.setPhrase(resp.getPhrase());
				exefr.setRequest(cmd);
				return exefr;
			}
			else
			{
				FuncResponse exefr = new FuncResponse();
				exefr.setCode(Response.INTERNAL_ERROR);
				exefr.setRequest(name + " " + cmd);
				HashMap<String, Object> entity = new HashMap<String, Object>();
				entity.put("message", "No SSH connection.");
				entity.put("code", Response.INTERNAL_ERROR);
				exefr.setEntity(entity);
				exefr.setPhrase("No SSH connection.");
				return exefr;
			}
		case "ssh_close":
			String temp = trim(expend(argStr, table));
			temp = temp.isEmpty() ? "__ssh_connection__" : temp;
			connId = table.getParent().lcontains("ssh_id") 
			          ? trim(expendVar("ssh_id", table.getParent())) 
			          : temp;
			ctx.closeResource(connId);
			
			FuncResponse clfr = new FuncResponse();
			clfr.setCode(Response.OK);
			clfr.setRequest("ssh_close()");
			clfr.setEntity(new HashMap<String, Object>());
			return clfr;
		}
		
		throw new DataInvalidException("Unsupport SSH Func: " + name);
	}
	
	/**
	 * autosend("password: ", 123\n, "yes/no: ", yes\n)
	 * ends(>, #)
	 * timtout(5000)
	 * clean_setting()
	 * 
	 * @param ssh
	 * @param table
	 * @throws ParamIncertitudeException 
	 */
	private void setConn(SshConnection ssh, ParameterTable table) throws DataInvalidException, ParamIncertitudeException
	{
		if (table.getParent().lcontains("clean_setting"))
		{
			ssh.clean();
		}
		
		if (table.getParent().lcontains("autosend"))
		{
			String astr = table.getParent().getPara("autosend", "");
			String[] ass = readFuncParam(astr);
			if (ass.length % 2 == 0)
			{
				NVar nvar = new NVar();
				for (int i = 0; i <= ass.length - 2; i += 2)
				{
					nvar.add(ass[i], sendStr(expend(ass[i + 1], table)));
				}
				ssh.with(nvar);
			}
			else
			{
				throw new DataInvalidException("autosend invalid: " + astr);
			}
		}
		
		if (table.getParent().lcontains("ends"))
		{
			String endstr = table.getParent().getPara("ends", "");
			String[] ess = readFuncParam(endstr);
			ssh.with(ess);
		}
		
	}

	private static class SSHCloseableResource implements ICloseable
	{
		SshConnection ssh = null;
		
		public SshConnection getSsh() {
			return ssh;
		}

		SSHCloseableResource(SshConnection sshconn){
			ssh = sshconn;
		}
		
		public void close() {
			ssh.close();
		}
		
	}
	
	private String trimExecLine(String content){
		int i = content.indexOf('\n');
		if (i < 0) return content;
		
		String rlt = content.substring(i + 1);
		i = rlt.lastIndexOf('\n');
		if (i < 0) return rlt;
		return rlt.substring(0, i).trim();
	}
	
}
