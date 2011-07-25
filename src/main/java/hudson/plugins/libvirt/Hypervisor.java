/**
 *  Copyright (C) 2010, Byte-Code srl <http://www.byte-code.com>
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Date: Mar 04, 2010
 * Author: Marco Mornati<mmornati@byte-code.com>
 */
package hudson.plugins.libvirt;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.libvirt.Connect;
import org.libvirt.ConnectAuth;
import org.libvirt.ConnectAuth.CredentialType;
import org.libvirt.Domain;
import org.libvirt.LibvirtException;

/**
 * Represents a virtual datacenter.
 */
public class Hypervisor extends Cloud {

    private static final Logger LOGGER = Logger.getLogger(Hypervisor.class.getName());
    private final String hypervisorType;
    private final String hypervisorHost;
    private final String hypervisorSystemUrl;
    private final int hypervisorSshPort;
    private final String username;
    private final String password;
    private transient Map<String, Domain> domains = null;
    private transient List<VirtualMachine> virtualMachineList = null;
    private transient Connect hypervisorConnection = null;
    private static final String ESX_HYPERVISOR = "ESX";
    private static final String VPX_HYPERVISOR = "VPX";

    @DataBoundConstructor
    public Hypervisor(String hypervisorType, String hypervisorHost, int hypervisorSshPort, String hypervisorSystemUrl, String username, String password) {
        super("Hypervisor(libvirt)");
        this.hypervisorType = hypervisorType;
        this.hypervisorHost = hypervisorHost;
        if (hypervisorSystemUrl != null && !hypervisorSystemUrl.equals("")) {
            this.hypervisorSystemUrl = hypervisorSystemUrl;
        } else {
            this.hypervisorSystemUrl = "system";
        }
        this.hypervisorSshPort = hypervisorSshPort <= 0 ? 22 : hypervisorSshPort;
        this.username = username;
        this.password = password;
        virtualMachineList = retrieveVirtualMachines();
    }

    private static Connect makeConnection(String type, String host, String user, String password, int port, String systemURL) {
        Connect newConnection = null;
        String hypervisorUri = constructHypervisorURI(type, host, user, port, systemURL);
        LOGGER.log(Level.INFO, "Trying to establish a connection to hypervisor URI: {0} as {1}/******",
                new Object[]{hypervisorUri, user});
        try {
            ConnectAuth auth = createConnectionAuth(user,password);
            newConnection = new Connect(hypervisorUri, auth, 0);
            LOGGER.log(Level.INFO, "Established connection to hypervisor URI: {0} as {1}/******",
                    new Object[]{hypervisorUri, user});
        } catch (LibvirtException e) {
            LogRecord rec = new LogRecord(Level.WARNING,
                    "Failed to establish connection to hypervisor URI: {0} as {1}/******");
            rec.setThrown(e);
            rec.setParameters(new Object[]{hypervisorUri, user});
            LOGGER.log(rec);
        }
        return newConnection;
    }

    private static ConnectAuth createConnectionAuth(final String user,final String password) {
        ConnectAuth auth = new ConnectAuth() {

            @Override
            public int callback(Credential[] arg0) {
                LOGGER.info("Callback");
                for (Credential cred : arg0) {
                    LOGGER.info(cred.type.toString());
                    if (cred.type == CredentialType.VIR_CRED_AUTHNAME) {
                        cred.result = user;
                    }
                    if (cred.type == CredentialType.VIR_CRED_PASSPHRASE) {
                        cred.result = password;
                    }
                }
                return 0;
            }
            
        };
        auth.credType = new CredentialType[]{CredentialType.VIR_CRED_AUTHNAME,CredentialType.VIR_CRED_PASSPHRASE};
        return auth;
    }

    private List<VirtualMachine> retrieveVirtualMachines() {
        List<VirtualMachine> vmList = new ArrayList<VirtualMachine>();
        try {
            domains = getDomains();
            for (String domainName : domains.keySet()) {
                vmList.add(new VirtualMachine(this, domainName));
            }
        } catch (Exception e) {
            LogRecord rec = new LogRecord(Level.SEVERE, "Cannot connect to datacenter {0} as {1}/******");
            rec.setThrown(e);
            rec.setParameters(new Object[]{hypervisorHost, username});
            LOGGER.log(rec);
        }
        return vmList;
    }

    public String getHypervisorHost() {
        return hypervisorHost;
    }

    public int getHypervisorSshPort() {
        return hypervisorSshPort;
    }

    public String getHypervisorType() {
        return hypervisorType;
    }

    public String getHypervisorSystemUrl() {
        return hypervisorSystemUrl;
    }

    public String getUsername() {
        return username;
    }

    public String getHypervisorDescription() {
        return getHypervisorType() + " - " + getHypervisorHost();
    }
    private Connect getConnection() throws LibvirtException {
        if (hypervisorConnection == null || !hypervisorConnection.isConnected()) {
            hypervisorConnection = makeConnection(hypervisorType, hypervisorHost, username, password, hypervisorSshPort, hypervisorSystemUrl);     
        }
        return hypervisorConnection;
    }
    public Domain getDomain(String name) throws LibvirtException {
        hypervisorConnection = getConnection();
        LogRecord info = new LogRecord(Level.INFO, "Getting hypervisor domains");
        LOGGER.log(info);
        if (hypervisorConnection != null) {
            return hypervisorConnection.domainLookupByName(name);
        } else {
            return null;
        }
    }
    public synchronized Map<String, Domain> getDomains() throws LibvirtException {
        Map<String, Domain> domains = new HashMap<String, Domain>();
        hypervisorConnection = getConnection();
        LogRecord info = new LogRecord(Level.INFO, "Getting hypervisor domains");
        LOGGER.log(info);
        if (hypervisorConnection != null) {
            for (String c : hypervisorConnection.listDefinedDomains()) {
                if (c != null && !c.equals("")) {
                    Domain domain = null;
                    try {
                        domain = hypervisorConnection.domainLookupByName(c);
                        domains.put(domain.getName(), domain);
                    } catch (Exception e) {
                        LogRecord rec = new LogRecord(Level.INFO, "Error retreiving information for domain with name: {0}");
                        rec.setParameters(new Object[]{c});
                        rec.setThrown(e);
                        LOGGER.log(rec);
                    }
                }
            }
            for (int c : hypervisorConnection.listDomains()) {
                Domain domain = null;
                try {
                    domain = hypervisorConnection.domainLookupByID(c);
                    domains.put(domain.getName(), domain);
                } catch (Exception e) {
                    LogRecord rec = new LogRecord(Level.INFO, "Error retreiving information for domain with id: {0}");
                    rec.setParameters(new Object[]{c});
                    rec.setThrown(e);
                    LOGGER.log(rec);
                }
            }           
        } else {
            LogRecord rec = new LogRecord(Level.SEVERE, "Cannot connect to datacenter {0} as {1}/******");
            rec.setParameters(new Object[]{hypervisorHost, username});
            LOGGER.log(rec);
        }

        return domains;
    }

    public synchronized List<VirtualMachine> getVirtualMachines() {
        if (virtualMachineList == null) {
            virtualMachineList = retrieveVirtualMachines();
        }
        return virtualMachineList;
    }

    public Collection<NodeProvisioner.PlannedNode> provision(Label label, int i) {
        return Collections.emptySet();
    }

    public boolean canProvision(Label label) {
        return false;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("Hypervisor");
        sb.append("{hypervisorUri='").append(hypervisorHost).append('\'');
        sb.append(", username='").append(username).append('\'');
        sb.append('}');
        return sb.toString();
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    public static String constructHypervisorURI(String type, String host, String user, int sshPort, String systemUrl) {
        String url;
        if (type.equals(ESX_HYPERVISOR) || type.equals(VPX_HYPERVISOR)) {
            url = type.toLowerCase() + "://" + host + "/";
            if (type.equals(VPX_HYPERVISOR)) {
                url += systemUrl;
            }
            url += "?no_verify=1";
        } else {
            url = type.toLowerCase() + "+ssh://" + user + "@" + host + ":" + sshPort + "/" + systemUrl + "?no_tty=1";
        }
        return url;
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<Cloud> {

        public final ConcurrentMap<String, Hypervisor> hypervisors = new ConcurrentHashMap<String, Hypervisor>();
        private String hypervisorType;
        private String hypervisorHost;
        private String hypervisorSystemUrl;
        private int hypervisorSshPort;
        private String username;
        private String password;
        public String getDisplayName() {
            return "Hypervisor (via libvirt)";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject o) throws FormException {
            hypervisorType = o.getString("hypervisorType");
            hypervisorHost = o.getString("hypervisorHost");
            hypervisorSystemUrl = o.getString("hypervisorSystemUrl");
            hypervisorSshPort = o.getInt("hypervisorSshPort");
            username = o.getString("username");
            password = o.getString("password");
            save();
            return super.configure(req, o);
        }

        public FormValidation doTestConnection(
                @QueryParameter String hypervisorType, @QueryParameter String hypervisorHost, @QueryParameter String hypervisorSshPort,
                @QueryParameter String username,@QueryParameter String password, @QueryParameter String hypervisorSystemUrl) throws Exception, ServletException {
            try {
                if (hypervisorHost == null) {
                    return FormValidation.error("Hypervisor Host is not specified");
                }
                if (hypervisorType == null) {
                    return FormValidation.error("Hypervisor type is not specified");
                }
                if (username == null) {
                    return FormValidation.error("Username is not specified");
                }
                
                LogRecord rec = new LogRecord(Level.INFO,
                        "Testing connection to hypervisor: {0}");
                int port = -1;
                if (hypervisorSshPort != null) {
                    port = Integer.valueOf(hypervisorSshPort);
                }
                rec.setParameters(new Object[]{constructHypervisorURI(hypervisorType, hypervisorHost, username, port, hypervisorSystemUrl)});
                LOGGER.log(rec);                
                Connect hypervisorConnection  = makeConnection(hypervisorType, hypervisorHost, username, password, port, hypervisorSystemUrl);
                hypervisorConnection.close();
                return FormValidation.ok("Connected successfully");
            } catch (LibvirtException e) {
                LogRecord rec = new LogRecord(Level.WARNING,
                        "Failed to check hypervisor connection to {0} as {1}/******");
                rec.setThrown(e);
                rec.setParameters(new Object[]{hypervisorHost, username});
                LOGGER.log(rec);
                return FormValidation.error(e.getMessage());
            } catch (UnsatisfiedLinkError e) {
                LogRecord rec = new LogRecord(Level.WARNING,
                        "Failed to connect to hypervisor. Check libvirt installation on hudson machine!");
                rec.setThrown(e);
                rec.setParameters(new Object[]{hypervisorHost, username});
                LOGGER.log(rec);
                return FormValidation.error(e.getMessage());
            }
        }

        public String getHypervisorHost() {
            return hypervisorHost;
        }

        public int getHypervisorSshPort() {
            return hypervisorSshPort;
        }

        public String getHypervisorSystemUrl() {
            return hypervisorSystemUrl;
        }

        public String getHypervisorType() {
            return hypervisorType;
        }

        public String getUsername() {
            return username;
        }
        public String getPassword() {
            return password;
        }
        public List<String> getHypervisorTypes() {
            List<String> types = new ArrayList<String>();
            types.add("QEMU");
            types.add("XEN");
            types.add(ESX_HYPERVISOR);
            types.add(VPX_HYPERVISOR);
            return types;
        }
    }

    public void stop() throws LibvirtException {
        if (hypervisorConnection != null) {
            hypervisorConnection.close();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                LOGGER.warning(e.getMessage());
            }
            hypervisorConnection = null;
        }
        
    }
}
