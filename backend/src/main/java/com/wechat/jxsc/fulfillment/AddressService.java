package com.wechat.jxsc.fulfillment;

import com.wechat.jxsc.auth.*;
import com.wechat.jxsc.common.*;
import com.wechat.jxsc.common.commerce.*;

import jakarta.persistence.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class AddressService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.wechat.jxsc.auth.ClosedAccountPrivacy privacy;

    @org.springframework.beans.factory.annotation.Autowired private AccountLifecycle lifecycle;
    @PersistenceContext private EntityManager em;
    private final SensitiveCipher cipher;
    private final CommerceSupport support;

    public AddressService(SensitiveCipher cipher, CommerceSupport support) {
        this.cipher = cipher;
        this.support = support;
    }

    @Transactional
    public FulfillmentDtos.AddressView save(Long id, FulfillmentDtos.AddressWrite r) {
        lifecycle.customer();
        var actor = CurrentActor.require();
        actor.requireType(CurrentActor.Type.CUSTOMER);
        support.lock(UserAccount.class, actor.id());
        UserAddress a = id == null ? new UserAddress() : owned(id, true);
        if (id != null && a.getVersion() != r.version())
            throw CommerceSupport.conflict("VERSION_CONFLICT", "Reload address");
        if (r.isDefault()) {
            em.createQuery(
                            "update UserAddress a set a.isDefault=false, a.version=a.version+1"
                                    + " where a.userId=:u and a.isDefault=true")
                    .setParameter("u", actor.id())
                    .executeUpdate();
            if (id != null) em.refresh(a);
        }
        a.userId = actor.id();
        a.nameCipher = cipher.encrypt(r.name());
        a.phoneCipher = cipher.encrypt(r.phone());
        a.detailCipher = cipher.encrypt(r.detail());
        a.provinceCode = r.provinceCode();
        a.cityCode = r.cityCode();
        a.districtCode = r.districtCode();
        a.isDefault = r.isDefault();
        if (id == null) em.persist(a);
        em.flush();
        return view(a);
    }

    @Transactional
    public void delete(long id) {
        lifecycle.customer();
        var actor = CurrentActor.require();
        support.lock(UserAccount.class, actor.id());
        var a = owned(id, true);
        a.deleted = true;
        a.isDefault = false;
    }

    @Transactional(readOnly = true)
    public PageResult<FulfillmentDtos.AddressView> list(PageQuery page) {
        var actor = CurrentActor.require();
        actor.requireType(CurrentActor.Type.CUSTOMER);
        var q =
                em.createQuery(
                                "select a from UserAddress a where a.userId=:u and a.deleted=false"
                                        + " order by a.isDefault desc,a.id desc",
                                UserAddress.class)
                        .setParameter("u", actor.id());
        var items =
                q
                        .setFirstResult(page.offset())
                        .setMaxResults(page.pageSize())
                        .getResultList()
                        .stream()
                        .map(this::view)
                        .toList();
        long count =
                em.createQuery(
                                "select count(a) from UserAddress a where a.userId=:u and"
                                        + " a.deleted=false",
                                Long.class)
                        .setParameter("u", actor.id())
                        .getSingleResult();
        return new PageResult<>(items, page.page(), page.pageSize(), count);
    }

    public UserAddress owned(long id, boolean lock) {
        var a = lock ? support.lock(UserAddress.class, id) : em.find(UserAddress.class, id);
        if (a == null || a.deleted) throw CommerceSupport.missing();
        CurrentActor.require().requireOwner(a.userId);
        return a;
    }

    public FulfillmentDtos.AddressView view(UserAddress a) {
        return new FulfillmentDtos.AddressView(
                a.getId().toString(),
                a.getVersion(),
                cipher.decrypt(a.nameCipher),
                cipher.decrypt(a.phoneCipher),
                a.provinceCode,
                a.cityCode,
                a.districtCode,
                cipher.decrypt(a.detailCipher),
                a.isDefault);
    }

    public void snapshot(long orderId, UserAddress a) {
        var s = new OrderAddressSnapshot();
        s.orderId = orderId;
        s.nameCipher = a.nameCipher;
        s.phoneCipher = a.phoneCipher;
        s.detailCipher = a.detailCipher;
        s.provinceCode = a.provinceCode;
        s.cityCode = a.cityCode;
        s.districtCode = a.districtCode;
        em.persist(s);
    }

    public FulfillmentDtos.AddressView snapshotView(long orderId) {
        if (privacy.orderClosed(orderId)) return null;
        var rows =
                em.createQuery(
                                "select a from OrderAddressSnapshot a where a.orderId=:id",
                                OrderAddressSnapshot.class)
                        .setParameter("id", orderId)
                        .getResultList();
        if (rows.isEmpty()) return null;
        var a = rows.getFirst();
        return new FulfillmentDtos.AddressView(
                a.getId().toString(),
                a.getVersion(),
                cipher.decrypt(a.nameCipher),
                cipher.decrypt(a.phoneCipher),
                a.provinceCode,
                a.cityCode,
                a.districtCode,
                cipher.decrypt(a.detailCipher),
                false);
    }
}
