package com.vistaluxhms.controller;

import com.twilio.type.Client;
import com.vistaluxhms.entity.*;
import com.vistaluxhms.model.*;
import com.vistaluxhms.repository.Vlx_City_Master_Repository;
import com.vistaluxhms.services.*;
import com.vistaluxhms.util.VistaluxConstants;
import com.vistaluxhms.validator.LeadValidator;
import freemarker.template.TemplateException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import javax.mail.MessagingException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpSession;
import javax.transaction.Transactional;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

@Controller
public class LeadController {

    @Autowired
    UserDetailsServiceImpl userDetailsService;

    @Autowired
    ClientServicesImpl clientService;

    @Autowired
    Vlx_City_Master_Repository cityRepository;

    @Autowired
    VlxCommonServicesImpl commonService;

    @Autowired
    SalesRelatesServicesImpl salesService;

    @Autowired
    LeadValidator leadValidator;

    @Autowired
    LeadServicesImpl leadService;
    @Autowired
    EmailServiceImpl emailService;

    @Value("${email.client.valid}")
    private boolean emailClientNotifyActive;

    SimpleDateFormat formatter = new SimpleDateFormat("dd-MM-yyyy");

    private UserDetailsObj getLoggedInUser() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        String username;
        if (principal instanceof UserDetails) {
            username = ((UserDetails)principal).getUsername();
        } else {
            username = principal.toString();
        }
        UserDetailsObj userObj = (UserDetailsObj) userDetailsService.loadUserByUsername(username);

        return userObj;
    }

    @RequestMapping("view_add_lead_form")
    public ModelAndView view_add_lead_form(@ModelAttribute("LEAD_OBJ") LeadEntityDTO leadEntityDTO, BindingResult result) {
        UserDetailsObj userObj = getLoggedInUser();
        ModelAndView modelView = new ModelAndView("leads/createLead");
        Map<Long, String> mapSalesPartner =  salesService.getActiveSalesPartnerMap(true);
        modelView.addObject("SALES_PARTNER_MAP", mapSalesPartner);
        List<UserDetailsObj> activeUsersList = userDetailsService.findAllActiveUsers();
        Map<Integer, String> activeUsersMap = (Map<Integer, String>) activeUsersList.stream().collect(
                Collectors.toMap(UserDetailsObj::getUserId, UserDetailsObj::getUsername));
        modelView.addObject("ACTIVE_USERS_MAP", activeUsersMap);
        List<WorkLoadStatusVO> lead_wl_statusList = commonService.find_All_Active_Status_Workload_Obj(VistaluxConstants.WORKLOAD_LEAD_STATUS);
        Map<Integer, String> leadStatusMap = (Map<Integer, String>) lead_wl_statusList.stream().collect(
                Collectors.toMap(WorkLoadStatusVO::getWorkloadStatusId, WorkLoadStatusVO::getWorkloadStatusName));
        modelView.addObject("LEAD_STATUS_MAP", leadStatusMap);

        modelView.addObject("userName", userObj.getUsername());
        return modelView;
    }

    @Transactional
    @PostMapping("create_create_lead")
    public ModelAndView create_create_lead(@ModelAttribute("LEAD_OBJ") LeadEntityDTO leadRecorderObj,  BindingResult result,final RedirectAttributes redirectAttrib ) {
        UserDetailsObj userObj = getLoggedInUser();
        //leadRecorderObj.setLeadStatus(UdanChooConstants.LEAD_CLOSE_REASON.get(UdanChooConstants.LEAD_NEW));
        System.out.println(leadRecorderObj);
        System.out.println("Client Details entered is " + leadRecorderObj.getClient());
        if(leadRecorderObj.getLeadOwner()==0) {
            leadRecorderObj.setLeadOwner(userObj.getUserId());
        }
        ModelAndView modelView = new ModelAndView();
        leadValidator.validate(leadRecorderObj, result);

        if(result.hasErrors()) {
            modelView = view_add_lead_form(leadRecorderObj, result);
            return modelView;
        }else {
            LeadEntity leadEntity = new LeadEntity(leadRecorderObj);
            ClientEntity clientEntity = clientService.findClientById(leadRecorderObj.getClient().getClientId());
            leadEntity.setClient(clientEntity);
            //SalesPartnerEntity salesPartnerEntity = salesService.findSalesPartnerById(leadRecorderObj//.getLeadSource().getSalesPartnerId());
            //leadEntity.setLeadSource(salesPartnerEntity);
            leadService.saveLead(leadEntity);
            leadRecorderObj.setLeadId(leadEntity.getLeadId());
            redirectAttrib.addFlashAttribute("Success", "Lead Record is updated Successfully..");
            modelView.setViewName("redirect:view_add_lead_form?leadId="+leadEntity.getLeadId());
            if(leadRecorderObj.isLeadCreationClientInformed()) {
                System.out.println("Lead Creation Client Informed");
                if(leadRecorderObj.isNotifyEmail()) {
                    notifyLeadCreationTargetAudience(leadRecorderObj, "LeadCreateConfirmation.ftl", true, true);
                }else{
                    System.out.println("Inform Client Active but email disabled. ");
                }
                //notifyLeadCreationSms(leadRecorderObj);
            }
            //write email code here.
        }
        return modelView;
    }

    private void notifyLeadCreationTargetAudience(LeadEntityDTO leadRecorderObj, String templateName,boolean isCreated,boolean markClient) {
        if(emailClientNotifyActive) {
            Mail mail = new Mail();
            String leadReferenceNumber = "ATT-" + leadRecorderObj.getLeadId();
            String guestDetails = "Adults:" + leadRecorderObj.getAdults() + " Children: " + (leadRecorderObj.getCwb() + leadRecorderObj.getCnb() + leadRecorderObj.getCompChild());
            String emailSubject = generateSubject(leadRecorderObj,guestDetails,leadReferenceNumber,isCreated);
            String servicesList ="";
            if(leadRecorderObj.isFit()) servicesList = servicesList + "Stay";
            if(leadRecorderObj.isGroupEvent()) servicesList = servicesList + "| Group ";
            if(leadRecorderObj.isMarriage()) servicesList = servicesList + " | Marriage";
            if(leadRecorderObj.isOthers()) servicesList = servicesList + " | Others";

            ClientEntity clientEntity =clientService.findClientById(leadRecorderObj.getClient().getClientId());
            mail.setSubject(emailSubject);
            AshokaTeam userObj = userDetailsService.findUserByID(leadRecorderObj.getLeadOwner());
            if(markClient) {
                mail.setTo(clientEntity.getEmailId());
                mail.setCc(userObj.getEmail());
            }else {
                mail.setTo(userObj.getEmail());
            }
            mail.setCc(getLoggedInUser().getEmail());
            try {
                Map<String, Object> model = new HashMap<String, Object>();
                model.put("leadId", leadReferenceNumber);
                model.put("contactName",clientEntity.getClientName());
                model.put("guestDetails", guestDetails);
                model.put("checkInDate", formatter.format(leadRecorderObj.getCheckInDate()));
                model.put("checkOutDate", formatter.format(leadRecorderObj.getCheckOutDate()));
                model.put("Services", servicesList);
                model.put("clientRemarks", leadRecorderObj.getClientRemarks());
                model.put("serviceAdvisor", userObj.getName());
                model.put("contactNumber", userObj.getMobile());
                mail.setModel(model);
                emailService.sendEmailMessageUsingTemplate(mail,templateName);
            } catch (MessagingException | IOException | TemplateException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
        else {
            System.out.println("Email Notification DISABLE. ");
        }
    }
    private String generateSubject(LeadEntityDTO leadRecorderObj,String guestDetails,String leadReferenceNumber,boolean isCreated) {
        String emailSubject;
        if(isCreated) {
            emailSubject = "Query Created with Query Id: " +leadReferenceNumber +" | "+  guestDetails + " | Check In " +  formatter.format(leadRecorderObj.getCheckInDate()) + " | Check Out " + formatter.format(leadRecorderObj.getCheckOutDate())   ;
        }
        else {
            emailSubject = "Query Updated with Query Id: " +leadReferenceNumber +" | "+  guestDetails + " | Check In " +  formatter.format(leadRecorderObj.getCheckInDate()) + "- | Check Out " + formatter.format(leadRecorderObj.getCheckOutDate())   ;
        }
        return emailSubject;
    }

    /*
    private int notifyLeadCreationSms(TgLeadsRecorderVO leadRecorderObj) {
        UserDetailsObj userObj = getLoggedInUser();
        ClientObj client = clientService.find_ClientBy_Id(leadRecorderObj.getContactId());
        Map<String, Object> smsValuesMap=new HashMap<String, Object>();
        smsValuesMap.put("CONTACT_NAME",client.getClientName());
        smsValuesMap.put("QueryId","Q-"+ leadRecorderObj.getLeadId()+ "-" + leadRecorderObj.getLeadSourceShortName());
        smsValuesMap.put("COMPANY_NAME",COMPANY_NAME);
        smsValuesMap.put("USER_NAME",userObj.getName());
        smsValuesMap.put("USER_MOBILE",userObj.getMobile());

        String message = UdanChooUtil.notificationMessagesList().getProperty(UdanChooConstants.QUERY_REGISTRATION_MSG);
        SMS smsMessage = new SMS();
        smsMessage.setTo(client.getMobile());
        smsMessage.setMessage(message);
        int returnCode =reminderService.sendSms(String.valueOf(smsMessage.getTo()),smsMessage.getMessage());
        return returnCode;
    }

*/

    @RequestMapping(value="view_filter_leads",method= {RequestMethod.GET,RequestMethod.POST})
    public ModelAndView view_filter_leads( @RequestParam(defaultValue = "0") String page,@RequestParam(defaultValue = VistaluxConstants.DEFAULT_PAGE_SIZE) Integer pageSize, @RequestParam(defaultValue = "CreatedAt") String sortBy,@ModelAttribute("FILTER_LEAD_WL") FilterLeadObj filterObj,BindingResult result) {

        ModelAndView modelView = new ModelAndView("leads/view_filterLeads");
        //System.out.println(filterObj);
        List<WorkLoadStatusVO> lead_wl_statusList = commonService.find_All_Active_Status_Workload_Obj(VistaluxConstants.WORKLOAD_LEAD_STATUS);

        // Create a LinkedHashMap to preserve the insertion order
        Map<Integer, String> leadStatusMap = new LinkedHashMap<>();

        // Manually put the constants first so they appear at the top
        leadStatusMap.put(VistaluxConstants.VIEW_ALL_OPEN_LEADS_WL_STATUS, "***All Open Leads***");
        leadStatusMap.put(VistaluxConstants.VIEW_ALL_LEADS_WL_STATUS, "***All Leads***");
        leadStatusMap.put(VistaluxConstants.VIEW_ALL_CLOSED_LEADS_WL_STATUS, "***All Closed Leads***");
        lead_wl_statusList.stream()
                .sorted(Comparator.comparing(WorkLoadStatusVO::getWorkloadStatusId)) // Optional: Sort by name if needed
                .forEach(status -> leadStatusMap.put(status.getWorkloadStatusId(), status.getWorkloadStatusName()));


        modelView.addObject("LEAD_STATUS_MAP", leadStatusMap);

        Map<Long, String> mapSalesPartner =  salesService.getActiveSalesPartnerMap(true);
        modelView.addObject("SALES_PARTNER_MAP", mapSalesPartner);

        List<UserDetailsObj> activeUsersList = userDetailsService.findAllActiveUsers();
        Map<Integer, String> activeUsersMap = (Map<Integer, String>) activeUsersList.stream().collect(
                Collectors.toMap(UserDetailsObj::getUserId, UserDetailsObj::getUsername));
        modelView.addObject("ACTIVE_USERS_MAP", activeUsersMap);

        //filterLeadValidator.validate(filterObj, result);
        if(result.hasErrors()) {
            return modelView;
        }
        UserDetailsObj user = getLoggedInUser();
        //filterObj.setLeadOwner(user.getUserId());
        boolean isAdmin=false;
        if(user.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("LEAD_MANAGER"))) {
            isAdmin=true;
        }

        //System.out.println("Lead Filter Owner is " + filterObj.getLeadOwner());

        if((!isAdmin) && filterObj.getLeadOwner()==0) {
            filterObj.setLeadOwner(user.getUserId());
        }

        int pageNum = Integer.parseInt(page);
        Page<LeadEntity> pageLeadsFilteredRecords = leadService.filterLeads(pageNum, pageSize, filterObj.getLeadOwner(), sortBy, filterObj, isAdmin);
        List<LeadEntityDTO> filteredLeadsVoList = generateFilteredLeadsVo(pageLeadsFilteredRecords);
        modelView.addObject("FILTERED_LEADS_RECORDS",filteredLeadsVoList);
        modelView.addObject("currentPage", page);
        modelView.addObject("totalPages", pageLeadsFilteredRecords.getTotalPages());
        modelView.addObject("totalLeads", pageLeadsFilteredRecords.getTotalElements());
        modelView.addObject("pageSize", pageSize);
        modelView.addObject("maxPages", pageLeadsFilteredRecords.getTotalPages());
        modelView.addObject("page", page);
        modelView.addObject("sortBy", sortBy);
        modelView.addObject("leadStatus", filterObj.getLeadStatus());

        return modelView;
    }

    private List<LeadEntityDTO> generateFilteredLeadsVo(Page<LeadEntity> pagedResult) {
        List<LeadEntityDTO> filteredLeadsVoList = new ArrayList<LeadEntityDTO>();
        List<LeadEntity> leadsEntityList = pagedResult.getContent();
        Iterator filteredLeadsIterator = leadsEntityList.iterator();
        while(filteredLeadsIterator.hasNext()) {
            LeadEntity leadEntity = (LeadEntity) filteredLeadsIterator.next();
            LeadEntityDTO leadsVO =new LeadEntityDTO();
            leadsVO.updateLeadVoFromEntity(leadEntity);
            ClientEntity clientEntity =clientService.findClientById(leadEntity.getClient().getClientId());
            leadsVO.setClientName(clientEntity.getClientName());
            leadsVO.setB2b(clientEntity.getB2b());
            leadsVO.setLeadOwnerName(userDetailsService.findUserByID(leadEntity.getLeadOwner()).getUsername());
            leadsVO.setStatusName(commonService.findWorkLoadStatusById(leadEntity.getLeadStatus()).getWorkloadStatusName());
            filteredLeadsVoList.add(leadsVO);
        }
        return filteredLeadsVoList;
    }


    @RequestMapping("view_lead_details_modal")
    public String view_lead_details_modal(@RequestParam long leadId, Model model){
        LeadEntity leadEntity =leadService.findLeadById(leadId);
        LeadEntityDTO leadsVO =new LeadEntityDTO();
        leadsVO.updateLeadVoFromEntity(leadEntity);
        ClientEntity clientEntity =clientService.findClientById(leadEntity.getClient().getClientId());
        leadsVO.setClientName(clientEntity.getClientName());
        leadsVO.setB2b(clientEntity.getB2b());
        leadsVO.setLeadOwnerName(userDetailsService.findUserByID(leadEntity.getLeadOwner()).getUsername());
        leadsVO.setStatusName(commonService.findWorkLoadStatusById(leadEntity.getLeadStatus()).getWorkloadStatusName());

        model.addAttribute("LEAD_OBJ",leadsVO );
        //return "leads/viewLeadDetails";
        return "leads/viewLeadDetails_modal";
    }

    @RequestMapping(value="view_edit_lead_form",method= {RequestMethod.GET,RequestMethod.POST})
    //@PostMapping("/form_view_editlead")
    public ModelAndView form_view_editlead(@ModelAttribute("LEAD_OBJ") LeadEntityDTO leadRecorderVO,BindingResult result) {
        ModelAndView modelView = view_add_lead_form(leadRecorderVO,result);
        LeadEntity leadEntity = leadService.findLeadById(leadRecorderVO.getLeadId());
        leadRecorderVO.updateLeadVoFromEntity(leadEntity);
        leadRecorderVO.setNotifyEmail(false);
        leadRecorderVO.setNotifySMS(false);
        leadRecorderVO.setNotifyWhatsapp(false);
        /*HashSet operatingTeam= new HashSet();
        tgLeadEntity.getTeam().forEach(e->operatingTeam.add(String.valueOf(e.getUserId())));
        leadRecorderVO.setOperatingTeams(operatingTeam);
        List<Object> jsonList = new ArrayList();
        Iterator itr = leadRecorderVO.getOperatingTeams().iterator();
        while(itr.hasNext()) {
            int jsonString = Integer.parseInt((String) itr.next());
            JSONObject opDestin = new JSONObject();
            JSONArray array = new JSONArray();
            opDestin.put("id", jsonString);
            UdnTeam team = userService.findUserByID(jsonString);
            opDestin.put("tagName", team.getUsername() + "--" + team.getName());
            jsonList.add(opDestin);
        }
        JSONArray myArray = new JSONArray(jsonList);
        String arrayToJson = myArray.toString(2);
        leadRecorderVO.setTeamNames(arrayToJson);
        */
        leadRecorderVO.setClientName(clientService.findClientById(leadRecorderVO.getClient().getClientId()).getClientName());
        //TODO following db call is also done inside form_register_newlead as well. this can be reduced. Think it over.
        //leadRecorderVO.setStatusName(commonService.find_DealStatusById(leadRecorderVO.getLeadStatus()).getWorkloadStatusName());
        /*List<UdnDealStatusVO> lead_wl_statusList = commonService.find_All_Active_Status_Workload_Obj(VistaluxConstants.WORKLOAD_LEAD_STATUS);
        Map<Integer, String> leadStatusMap = (Map<Integer, String>) lead_wl_statusList.stream().collect(
                Collectors.toMap(UdnDealStatusVO::getWorkloadStatusId, UdnDealStatusVO::getWorkloadStatusName));
        modelView.addObject("LEAD_STATUS_MAP", leadStatusMap);
    */
        leadRecorderVO.setLeadOwnerName(userDetailsService.findUserByID(leadRecorderVO.getLeadOwner()).getUsername());
        modelView.setViewName("leads/editLead");
        return modelView;
    }

    @Transactional
    @PostMapping("edit_edit_lead")
    public ModelAndView edit_edit_lead(@ModelAttribute("LEAD_OBJ") LeadEntityDTO leadRecorderObj,  BindingResult result,final RedirectAttributes redirectAttrib ) {
        UserDetailsObj userObj = getLoggedInUser();
        //leadRecorderObj.setLeadStatus(UdanChooConstants.LEAD_CLOSE_REASON.get(UdanChooConstants.LEAD_NEW));
        if(leadRecorderObj.getLeadOwner()==0) {
            leadRecorderObj.setLeadOwner(userObj.getUserId());
        }
        ModelAndView modelView = new ModelAndView();
        leadValidator.validate(leadRecorderObj, result);
        if(result.hasErrors()) {
            modelView = form_view_editlead(leadRecorderObj, result);
            return modelView;
        }else {
            LeadEntity orgEntity = leadService.findLeadById(leadRecorderObj.getLeadId());
            long orgLeadOwner = orgEntity.getLeadOwner();
            LeadEntity leadEntity = new LeadEntity(leadRecorderObj);
            leadEntity.setLeadId(leadRecorderObj.getLeadId());
            ClientEntity clientEntity = clientService.findClientById(leadRecorderObj.getClient().getClientId());
            leadEntity.setClient(clientEntity);
            long newLeadOwner = leadEntity.getLeadOwner();
            /*leadRecorderObj.getOperatingTeams().forEach((e) -> {
                UdnTeam userEntity = userService.findUserByID(Integer.parseInt(e));
                tgLeadEntity.getTeam().add(userEntity);
            });*/

            leadService.saveLead(leadEntity);
            redirectAttrib.addFlashAttribute("Success", "Lead Record is updated Successfully..");
            modelView.setViewName("redirect:view_filter_leads?leadId="+leadEntity.getLeadId());

            /*if(orgLeadOwner!=newLeadOwner) {
                notifyLeadCreationTargetAudience(leadRecorderObj,"LeadAssignmentConfirmation.ftl",false,false);
            }*/
            //if(leadRecorderObj.) {

                // notifyLeadCreationTargetAudience(leadRecorderObj,"LeadUpdateConfirmation.ftl",false,true);
                //notifyLeadCreationSms(leadRecorderObj);
            //}
            //write email code here.
        }
        return modelView;
    }




}
